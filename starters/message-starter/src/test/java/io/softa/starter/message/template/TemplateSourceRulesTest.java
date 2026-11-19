package io.softa.starter.message.template;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.annotation.TemplateVariables;
import io.softa.framework.orm.meta.TemplateVariablesRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateSourceRulesTest {

    public record Line(String name, int qty) {
    }

    public record Sender(String fullName) {
    }

    @TemplateVariables(name = "test.notice")
    public record Notice(@Schema(title = "Record code") String recordCode, String link, BigDecimal amount,
                         boolean urgent, Sender sender, List<Line> lines) {
    }

    private TemplateTestModels models;
    private TemplateSourceRules rules;

    @BeforeEach
    void setUp() {
        TemplateVariablesRegistry.register(Set.of(Notice.class));
        models = new TemplateTestModels();
        rules = new TemplateSourceRules(models);
    }

    @AfterEach
    void clear() {
        TemplateVariablesRegistry.register(Set.of());
    }

    private static String loop(String list, String... columns) {
        StringBuilder head = new StringBuilder();
        StringBuilder body = new StringBuilder();
        for (String column : columns) {
            head.append("<th data-tpl-field=\"").append(column).append("\">x</th>");
            body.append("<td>{{").append(column).append("}}</td>");
        }
        return "<table data-tpl-loop=\"" + list + "\"><thead><tr>" + head + "</tr></thead><tbody><tr>" + body
                + "</tr></tbody></table>";
    }

    private static String condition(String field, String op, String value, String type) {
        return "<div data-tpl-if=\"" + field + "\" data-tpl-op=\"" + op + "\""
                + (value == null ? "" : " data-tpl-value=\"" + value + "\"")
                + (type == null ? "" : " data-tpl-value-type=\"" + type + "\"") + "><p>x</p></div>";
    }

    private List<String> bySet(String html) {
        return rules.problems("test.notice", null, TemplateUsage.ofMail(null, html, null));
    }

    private List<String> byModel(String html) {
        return rules.problems(null, "Order", TemplateUsage.ofMail(null, html, null));
    }

    // ---- no source, two sources ----

    @Test
    void aTemplateWithoutASourceIsNotChecked() {
        assertThat(rules.problems(null, " ", TemplateUsage.ofMail("{{ anything }}", "{{ at.all }}", null))).isEmpty();
    }

    @Test
    void aTemplateCannotNameBothSources() {
        assertThat(rules.problems("test.notice", "Order", TemplateUsage.ofMail(null, "x", null)))
                .singleElement().asString().contains("not both");
    }

    // ---- a declared set ----

    @Test
    void aSetThatIsNotDeclaredIsRefused() {
        assertThat(rules.problems("test.gone", null, TemplateUsage.ofMail(null, "x", null)))
                .singleElement().asString().contains("'test.gone'");
    }

    @Test
    void everyVariableOfTheSetPasses_nestedOnesIncluded() {
        assertThat(rules.problems("test.notice", null, TemplateUsage.ofMail("{{ recordCode }}",
                "<a href=\"{{ link }}\">{{ sender.fullName }}</a> {{ amount | numberformat }}", "{{ link }}"))).isEmpty();
    }

    @Test
    void unknownVariablesAreListedOnceWithWhatTheSetOffers() {
        assertThat(bySet("{{ recordCode }} {{ recordCod }} {{ sender.name }}")).singleElement().asString()
                .contains("Unknown variables: recordCod, sender.name")
                .contains("'test.notice' offers: recordCode, link, amount, urgent, sender, lines");
    }

    @Test
    void aLoopTableNeedsAListAndItemFields() {
        assertThat(bySet(loop("lines", "name", "qty"))).isEmpty();
        assertThat(bySet(loop("lines", "name", "price"))).singleElement().asString()
                .contains("'lines'").contains("'price'");
        assertThat(bySet(loop("link", "name"))).singleElement().asString().contains("'link' is looped over");
        assertThat(bySet(loop("rows", "name"))).singleElement().asString().contains("Unknown variable: rows");
    }

    @Test
    void aConditionMustFitTheTypeOfItsField() {
        assertThat(bySet(condition("urgent", "isTrue", null, null))).isEmpty();
        assertThat(bySet(condition("amount", "gt", "1000", "number"))).isEmpty();
        assertThat(bySet(condition("recordCode", "eq", "LR-1", "string"))).isEmpty();
        assertThat(bySet(condition("lines", "isNotEmpty", null, null))).isEmpty();
        assertThat(bySet(condition("recordCode", "isTrue", null, null))).singleElement().asString()
                .contains("not a yes/no field");
        assertThat(bySet(condition("recordCode", "gt", "1", "number"))).singleElement().asString()
                .contains("only a number");
        assertThat(bySet(condition("amount", "eq", "1000", "string"))).singleElement().asString()
                .contains("string value").contains("holds a number");
        assertThat(bySet(condition("sender", "eq", "x", "string"))).singleElement().asString()
                .contains("only ask whether it is empty");
        assertThat(bySet(condition("missing", "isEmpty", null, null))).singleElement().asString()
                .contains("Unknown variable: missing");
    }

    // ---- a model ----

    @Test
    void aModelThatDoesNotExistIsRefused() {
        assertThat(rules.problems(null, "Nope", TemplateUsage.ofMail(null, "x", null)))
                .singleElement().asString().contains("'Nope'");
    }

    @Test
    void fieldsAndToOnePathsOfTheModelPass() {
        assertThat(rules.problems(null, "Order", TemplateUsage.ofMail("{{ code }}",
                "{{ customerId.name }} {{ customerId.managerId.name }} {{ dueDate }} {{ managerId.name }}", null)))
                .isEmpty();
    }

    @Test
    void aPathTheModelCannotAnswerIsRefusedByName() {
        assertThat(byModel("{{ cod }} {{ customerId.nam }} {{ code.x }}")).hasSize(3)
                .anySatisfy(p -> assertThat(p).startsWith("'cod'"))
                .anySatisfy(p -> assertThat(p).startsWith("'customerId.nam'"))
                .anySatisfy(p -> assertThat(p).startsWith("'code.x'"));
    }

    @Test
    void aListIsNotPrinted_itIsShownWithATable() {
        assertThat(byModel("{{ lines }}")).singleElement().asString().contains("'lines' is a list");
    }

    @Test
    void aRelationIsPrintedOneWay() {
        assertThat(byModel("{{ customerId }} {{ customerId.name }}")).singleElement().asString()
                .contains("'customerId' is printed both on its own and as 'customerId.name'");
        assertThat(byModel("{{ customerId }}")).isEmpty();
    }

    @Test
    void aFieldTheAuthorCannotSeeCannotGoIntoATemplate() {
        models.withheld = Map.of("Customer", Set.of("taxNumber"), "Order", Set.of("amount"));
        assertThat(byModel("{{ customerId.taxNumber }}")).singleElement().asString()
                .contains("'customerId.taxNumber'").contains("no access");
        assertThat(byModel(condition("amount", "gt", "1", "number"))).singleElement().asString().contains("no access");
        assertThat(byModel("{{ customerId.name }}")).isEmpty();
    }

    @Test
    void aLoopTableRepeatsAListOfTheModelWithFieldsOfItsItems() {
        assertThat(byModel(loop("lines", "name", "qty", "productId.code"))).isEmpty();
        assertThat(byModel(loop("lines", "name", "price"))).singleElement().asString().contains("'price'");
        assertThat(byModel(loop("lines", "tags"))).singleElement().asString().contains("itself a list");
        assertThat(byModel(loop("code", "name"))).singleElement().asString().contains("'code' is looped over");
        assertThat(byModel(loop("customerId.orders", "code"))).singleElement().asString()
                .contains("reached through another record");
        assertThat(byModel("{% for l in lines %}{{ l.name }}{% endfor %}")).isEmpty();
        assertThat(byModel("{% for l in code %}{{ l }}{% endfor %}")).singleElement().asString()
                .contains("'code' is looped over");
    }

    @Test
    void aConditionOnAModelFieldFitsItsType() {
        assertThat(byModel(condition("urgent", "isFalse", null, null)
                + condition("amount", "le", "10.5", "number")
                + condition("status", "ne", "DRAFT", "string")
                + condition("customerId.name", "eq", "Acme", "string")
                + condition("dueDate", "isEmpty", null, null)
                + condition("lines", "isEmpty", null, null))).isEmpty();
        assertThat(byModel(condition("customerId", "isNotEmpty", null, null))).isEmpty();
        // The same relation asked about on its own and through a field: one name, two shapes.
        assertThat(byModel(condition("customerId", "isNotEmpty", null, null)
                + condition("customerId.name", "eq", "Acme", "string"))).singleElement().asString()
                .contains("'customerId' is compared both on its own and as 'customerId.name'");
        assertThat(byModel(condition("dueDate", "eq", "2026-01-01", "string"))).singleElement().asString()
                .contains("only ask whether it is empty");
        // A value that is not of its type never gets this far: the block itself cannot be translated.
        assertThat(TemplateUsage.ofMail(null, condition("amount", "gt", "lots", "number"), null).blockError())
                .contains("'lots' is not a number");
        assertThat(byModel(condition("customerId.nam", "isEmpty", null, null))).singleElement().asString()
                .contains("The condition on 'customerId.nam'");
    }

    @Test
    void smsContentIsCheckedAgainstTheSetToo() {
        assertThat(rules.problems("test.notice", null, TemplateUsage.ofText("Code {{ recordCode }} {{ link }}")))
                .isEmpty();
        assertThat(rules.problems("test.notice", null, TemplateUsage.ofText("Code {{ code }}")))
                .singleElement().asString().contains("Unknown variable: code");
    }
}
