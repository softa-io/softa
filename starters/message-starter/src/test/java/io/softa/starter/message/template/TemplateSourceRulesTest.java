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

    private TemplateSourceRules rules;

    @BeforeEach
    void setUp() {
        TemplateVariablesRegistry.register(Set.of(Notice.class));
        rules = new TemplateSourceRules();
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
        return rules.problems("test.notice", TemplateUsage.ofMail(null, html, null));
    }

    // ---- no set ----

    @Test
    void aTemplateWithoutASetIsNotChecked() {
        assertThat(rules.problems(" ", TemplateUsage.ofMail("{{ anything }}", "{{ at.all }}", null))).isEmpty();
        assertThat(rules.problems(null, TemplateUsage.ofMail(null, "{{ x }}", null))).isEmpty();
    }

    // ---- a declared set ----

    @Test
    void aSetThatIsNotDeclaredIsRefused() {
        assertThat(rules.problems("test.gone", TemplateUsage.ofMail(null, "x", null)))
                .singleElement().asString().contains("'test.gone'");
    }

    @Test
    void everyVariableOfTheSetPasses_nestedOnesIncluded() {
        assertThat(rules.problems("test.notice", TemplateUsage.ofMail("{{ recordCode }}",
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

    @Test
    void smsContentIsCheckedAgainstTheSetToo() {
        assertThat(rules.problems("test.notice", TemplateUsage.ofText("Code {{ recordCode }} {{ link }}")))
                .isEmpty();
        assertThat(rules.problems("test.notice", TemplateUsage.ofText("Code {{ code }}")))
                .singleElement().asString().contains("Unknown variable: code");
    }

    // ---- the set is fixed once saved ----

    @Test
    void aSavedSetCannotBeChangedOrCleared() {
        Map<String, Object> stored = Map.of("variableSetName", "test.notice");
        assertThat(TemplateSourceRules.changedSetProblem(stored, "test.other")).get().asString()
                .contains("cannot be changed").contains("'test.notice'");
        assertThat(TemplateSourceRules.changedSetProblem(stored, "")).isPresent();
        assertThat(TemplateSourceRules.changedSetProblem(stored, null)).isPresent();
    }

    @Test
    void aSetIsGivenOnce_andWritingTheSameOneAgainPasses() {
        assertThat(TemplateSourceRules.changedSetProblem(Map.of(), "test.notice")).isEmpty();
        assertThat(TemplateSourceRules.changedSetProblem(null, "test.notice")).isEmpty();
        // A seed reload writes the row back whole, its set included.
        assertThat(TemplateSourceRules.changedSetProblem(Map.of("variableSetName", "test.notice"), " test.notice "))
                .isEmpty();
    }
}
