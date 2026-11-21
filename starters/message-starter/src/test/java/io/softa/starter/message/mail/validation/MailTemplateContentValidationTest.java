package io.softa.starter.message.mail.validation;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.annotation.TemplateVariables;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.TemplateVariablesRegistry;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.framework.orm.service.validation.WriteValidationException;
import io.softa.starter.message.mail.service.MailSendServerConfigService;
import io.softa.starter.message.mail.service.PlatformMailSendServerService;
import io.softa.starter.message.mail.service.PlatformMailTemplateService;
import io.softa.starter.message.template.TemplateSourceRules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The content of a mail template fits its source and compiles — on create, and on an update that
 * touches the source or the content ({@link MailTemplateWriteValidator}).
 */
class MailTemplateContentValidationTest {

    @TemplateVariables(name = "test.code-notice")
    public record CodeNotice(String code, int expiryMinutes) {
    }

    private MailTemplateWriteValidator validator;

    @BeforeEach
    void setUp() {
        TemplateVariablesRegistry.register(Set.of(CodeNotice.class));
        validator = new MailTemplateWriteValidator(mock(PlatformMailTemplateService.class),
                mock(MailSendServerConfigService.class), mock(PlatformMailSendServerService.class),
                new TemplateSourceRules());
    }

    @AfterEach
    void clear() {
        TemplateVariablesRegistry.register(Set.of());
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private WriteContext create(String model, Map<String, Object> row) {
        WriteContext ctx = WriteContext.of(model, AccessType.CREATE, row, null);
        validator.validateCreate(ctx);
        return ctx;
    }

    private WriteContext update(String model, Map<String, Object> stored, Map<String, Object> patch) {
        Map<String, Object> merged = new HashMap<>(stored);
        merged.putAll(patch);
        WriteContext ctx = WriteContext.of(model, AccessType.UPDATE, merged, patch, stored);
        validator.validateUpdate(ctx);
        return ctx;
    }

    private static Set<String> fieldsOf(WriteContext ctx) {
        return ctx.errors().stream().map(WriteValidationException.FieldError::field)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void aTemplateWithoutASourceTakesAnyVariable() {
        WriteContext ctx = create("MailTemplate", row("code", "x", "subject", "{{ whatever }}",
                "bodyHtml", "<p>{{ anything.at.all }}</p>"));
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void aVariableTheSetDoesNotHaveIsRefusedOnTheSource() {
        WriteContext ctx = create("PlatformMailTemplate", row("code", "user.verification-code",
                "variableSetName", "test.code-notice", "subject", "Code {{ code }}",
                "bodyHtml", "<p>{{ code }} valid {{ expiryMinute }} min</p>"));
        assertThat(ctx.errors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("variableSetName");
            assertThat(error.message()).contains("Unknown variable: expiryMinute").contains("code, expiryMinutes");
        });
    }

    @Test
    void aTemplateUsingOnlyTheSetsVariablesPasses_inFieldBlocksToo() {
        WriteContext ctx = create("MailTemplate", row("code", "x", "variableSetName", "test.code-notice",
                "subject", "Code {{ code }}",
                "bodyHtml", "<p><span data-tpl-field=\"code\" data-tpl-label=\"Code\">{{code}}</span> {{ expiryMinutes }}</p>",
                "bodyText", "{{ code }}"));
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void aBodyTheEngineCannotCompileIsRefused_withOrWithoutASource() {
        WriteContext ctx = create("MailTemplate", row("code", "x", "subject", "s",
                "bodyHtml", "<p>{% if count &gt; 1 %}many{% endif %}</p>", "bodyText", "{% for a in b %}"));
        assertThat(fieldsOf(ctx)).containsExactlyInAnyOrder("bodyHtml", "bodyText");
        assertThat(ctx.errors()).allSatisfy(error -> assertThat(error.message()).startsWith("The template cannot be read"));
    }

    @Test
    void aBlockThatCannotBeTranslatedIsRefusedOnTheBody() {
        WriteContext ctx = create("MailTemplate", row("code", "x", "subject", "s",
                "bodyHtml", "<div data-tpl-if=\"amount\" data-tpl-op=\"gt\" data-tpl-value=\"lots\" "
                        + "data-tpl-value-type=\"number\"><p>x</p></div>"));
        assertThat(ctx.errors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("bodyHtml");
            assertThat(error.message()).contains("'lots' is not a number");
        });
    }

    @Test
    void blocksAndHandWrittenTagsTogetherAreAccepted() {
        WriteContext ctx = create("MailTemplate", row("code", "x", "subject", "s",
                "bodyHtml", "<p>{% if count > 1 %}many{% endif %}</p>"
                        + "<table><tbody>{% for l in rows %}<tr><td>{{ l.name }}</td></tr>{% endfor %}</tbody></table>"
                        + "<div data-tpl-if=\"urgent\" data-tpl-op=\"isTrue\"><p>x</p></div>"));
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void aSavedSetCannotBeChanged() {
        Map<String, Object> stored = row("code", "x", "variableSetName", "test.code-notice",
                "subject", "s", "bodyHtml", "<p>x</p>");
        assertThat(update("MailTemplate", stored, row("variableSetName", "")).errors()).anySatisfy(error -> {
            assertThat(error.field()).isEqualTo("variableSetName");
            assertThat(error.message()).contains("cannot be changed");
        });
        assertThat(update("PlatformMailTemplate", stored, row("variableSetName", "test.code-notice")).hasErrors())
                .isFalse();
    }

    @Test
    void anUpdateThatLeavesSourceAndContentAloneIsNotChecked() {
        Map<String, Object> stored = row("code", "x", "variableSetName", "test.code-notice",
                "subject", "{{ gone }}", "bodyHtml", "<p>{{ gone }}</p>", "name", "Old");
        assertThat(update("MailTemplate", stored, row("name", "New")).hasErrors()).isFalse();
        // Touching the content checks the whole merged row.
        assertThat(update("MailTemplate", stored, row("subject", "Hello")).errors()).singleElement()
                .satisfies(error -> assertThat(error.message()).contains("Unknown variable: gone"));
        // So does binding a set to a template that had none.
        Map<String, Object> unbound = new HashMap<>(stored);
        unbound.remove("variableSetName");
        assertThat(update("MailTemplate", unbound, row("variableSetName", "test.code-notice")).hasErrors()).isTrue();
    }
}
