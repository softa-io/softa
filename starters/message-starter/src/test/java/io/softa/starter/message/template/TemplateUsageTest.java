package io.softa.starter.message.template;

import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.placeholder.TemplateBlocks;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateUsageTest {

    private static final String LOOP = "<table data-tpl-loop=\"lines\">"
            + "<thead><tr><th data-tpl-field=\"name\">Name</th><th data-tpl-field=\"productId.code\">Code</th></tr></thead>"
            + "<tbody><tr><td>{{name}}</td><td>{{productId.code}}</td></tr></tbody></table>";

    @Test
    void printedNamesComeFromTheSubjectAndBothBodies() {
        TemplateUsage usage = TemplateUsage.ofMail("Re: {{ recordCode }}",
                "<p>{{ employeeId.fullName }} <a href=\"{{ link }}\">open</a></p>", "Plain {{ flowName }}");
        assertThat(usage.printed()).containsExactly("recordCode", "employeeId.fullName", "link", "flowName");
        assertThat(usage.lists()).isEmpty();
        assertThat(usage.loops()).isEmpty();
        assertThat(usage.blockError()).isNull();
    }

    @Test
    void aFieldBlockIsItsPlaceholder() {
        TemplateUsage usage = TemplateUsage.ofMail(null,
                "<span data-tpl-field=\"recordCode\" data-tpl-label=\"Record code\">{{recordCode}}</span>", null);
        assertThat(usage.printed()).containsExactly("recordCode");
    }

    @Test
    void aFilteredNameIsStillTheName_andAnExpressionNamesNothing() {
        TemplateUsage usage = TemplateUsage.ofMail(null,
                "{{ name | upper }} {{ total + 1 }} {{ a || b }} {{ dueDate | date(\"dd MMM\") }}", null);
        assertThat(usage.printed()).containsExactly("name", "dueDate");
    }

    @Test
    void aLoopTableAsksForItsListAndItsColumns_notForVariablesNamedLikeItsCells() {
        TemplateUsage usage = TemplateUsage.ofMail("{{ title }}", "<p>{{ title }}</p>" + LOOP, null);
        assertThat(usage.printed()).containsExactly("title");
        assertThat(usage.lists()).containsExactly("lines");
        assertThat(usage.loops()).containsExactly(new TemplateBlocks.Loop("lines", List.of("name", "productId.code")));
    }

    @Test
    void aConditionAsksForItsFieldOnlyThroughTheCondition() {
        String html = "<div data-tpl-if=\"amount\" data-tpl-op=\"gt\" data-tpl-value=\"1000\" data-tpl-value-type=\"number\">"
                + "<p>{{ approver }}</p></div>";
        TemplateUsage usage = TemplateUsage.ofMail(null, html, null);
        assertThat(usage.printed()).containsExactly("approver");
        assertThat(usage.conditions()).extracting(TemplateBlocks.Condition::field).containsExactly("amount");
        // Printed as well: then it is asked for both ways.
        TemplateUsage both = TemplateUsage.ofMail(null, "<p>{{ amount }}</p>" + html, null);
        assertThat(both.printed()).containsExactly("amount", "approver");
    }

    @Test
    void handWrittenLoopsNameTheirListAndKeepTheirItemOut() {
        TemplateUsage usage = TemplateUsage.ofMail(null,
                "{% for item in items %}{{ item.name }}{{ loop.index }}{% endfor %}{% if urgent %}!{% endif %}", null);
        assertThat(usage.lists()).containsExactly("items");
        assertThat(usage.printed()).containsExactly("urgent");
    }

    @Test
    void aBlockThatCannotBeTranslatedIsReportedAndTheRestStillRead() {
        TemplateUsage usage = TemplateUsage.ofMail("{{ a }}",
                "<div data-tpl-if=\"x\" data-tpl-op=\"between\"><p>{{ b }}</p></div>", null);
        assertThat(usage.blockError()).contains("'between'");
        assertThat(usage.printed()).containsExactly("a", "b");
    }

    @Test
    void aTextTemplateNamesItsPlaceholders() {
        TemplateUsage usage = TemplateUsage.ofText("Code {{ code }}, valid {{expiryMinutes}} min. {{ a + b }}");
        assertThat(usage.printed()).containsExactly("code", "expiryMinutes");
        assertThat(TemplateUsage.ofText(null).isEmpty()).isTrue();
    }
}
