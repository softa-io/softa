package io.softa.starter.message.mail.service.impl;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.placeholder.TemplateBlocks;
import io.softa.starter.message.mail.entity.MailTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** A mail body's blocks are written out when it is rendered ({@link AbstractMailTemplateServiceImpl}). */
class MailTemplateBlockRenderingTest {

    private final MailTemplateServiceImpl service = new MailTemplateServiceImpl();

    private static MailTemplate template(String subject, String html) {
        MailTemplate template = new MailTemplate();
        template.setSubject(subject);
        template.setBodyHtml(html);
        return template;
    }

    private static final String BODY = "<p><span data-tpl-field=\"code\" data-tpl-label=\"Code\">{{code}}</span></p>"
            + "<div data-tpl-if=\"status\" data-tpl-op=\"eq\" data-tpl-value=\"APPROVED\"><p>Approved: {{ status }}</p></div>"
            + "<table data-tpl-loop=\"lines\"><thead><tr><th data-tpl-field=\"name\">Name</th></tr></thead>"
            + "<tbody><tr><td>{{name}}</td></tr></tbody></table>";

    @Test
    void variablesFromCodeAreComparedAsTheyAre() {
        String out = service.renderBodyHtml(template("s", BODY), Map.of("code", "SO-7", "status", "APPROVED",
                "lines", List.of(Map.of("name", "Pen"), Map.of("name", "Ink"))));

        assertThat(out).contains(">SO-7</span>").contains("Approved: APPROVED")
                .contains("<tbody><tr><td>Pen</td></tr><tr><td>Ink</td></tr></tbody>");
    }

    @Test
    void variablesReadFromARecordAreComparedByTheirStoredValuesAndPrintedByTheirDisplayOnes() {
        String out = service.renderBodyHtml(template("s", BODY), Map.of("code", "SO-7",
                "status", "Approved by manager", "lines", List.of(),
                TemplateBlocks.RAW_ROOT, Map.of("status", "APPROVED")));

        assertThat(out).contains("Approved: Approved by manager").contains("<tbody></tbody>");
    }

    @Test
    void aConditionThatDoesNotHoldRemovesItsBlock() {
        String out = service.renderBodyHtml(template("s", BODY), Map.of("code", "SO-7", "status", "DRAFT",
                "lines", List.of()));

        assertThat(out).doesNotContain("Approved").doesNotContain("data-tpl-if");
    }

    @Test
    void aBodyWithoutBlocksRendersAsItAlwaysDid() {
        String out = service.renderBodyHtml(template("s",
                "<table>{% for l in lines %}<tr><td>{{ l.name }}</td></tr>{% endfor %}</table>"),
                Map.of("lines", List.of(Map.of("name", "Pen"))));

        assertThat(out).isEqualTo("<table><tr><td>Pen</td></tr></table>");
    }

    @Test
    void theSubjectFollowsADottedNameIntoTheRecord() {
        assertThat(service.renderSubject(template("Order {{ code }} for {{ customerId.name }}", null),
                Map.of("code", "SO-7", "customerId", Map.of("name", "Acme")))).isEqualTo("Order SO-7 for Acme");
    }
}
