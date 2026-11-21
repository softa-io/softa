package io.softa.starter.message.sms.validation;

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
import io.softa.starter.message.template.TemplateSourceRules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SmsTemplateWriteValidatorTest {

    @TemplateVariables(name = "test.sms-code")
    public record SmsCode(String code, int expiryMinutes) {
    }

    private SmsTemplateWriteValidator validator;

    @BeforeEach
    void setUp() {
        TemplateVariablesRegistry.register(Set.of(SmsCode.class));
        validator = new SmsTemplateWriteValidator(new TemplateSourceRules());
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

    @Test
    void appliesToSmsTemplatesOnly() {
        assertThat(validator.supports("SmsTemplate")).isTrue();
        assertThat(validator.supports("MailTemplate")).isFalse();
    }

    @Test
    void contentUsingTheSetsVariablesPasses() {
        WriteContext ctx = WriteContext.of("SmsTemplate", AccessType.CREATE, row("code", "user.verification-code",
                "variableSetName", "test.sms-code", "content", "Code {{ code }}, valid {{expiryMinutes}} min."), null);
        validator.validateCreate(ctx);
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void aVariableTheSetDoesNotHaveIsRefused() {
        WriteContext ctx = WriteContext.of("SmsTemplate", AccessType.CREATE, row("code", "user.verification-code",
                "variableSetName", "test.sms-code", "content", "Code {{ codee }}"), null);
        validator.validateCreate(ctx);
        assertThat(ctx.errors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("variableSetName");
            assertThat(error.message()).contains("Unknown variable: codee");
        });
    }

    @Test
    void aTemplateWithoutASetIsNotChecked() {
        WriteContext ctx = WriteContext.of("SmsTemplate", AccessType.CREATE,
                row("code", "x", "content", "{{ anything }}"), null);
        validator.validateCreate(ctx);
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void anUpdateIsCheckedOnlyWhenItTouchesTheSetOrTheContent() {
        Map<String, Object> stored = row("code", "x", "variableSetName", "test.sms-code", "content", "{{ gone }}",
                "name", "Old");
        Map<String, Object> renamed = new HashMap<>(stored);
        renamed.put("name", "New");
        WriteContext rename = WriteContext.of("SmsTemplate", AccessType.UPDATE, renamed, row("name", "New"), stored);
        validator.validateUpdate(rename);
        assertThat(rename.hasErrors()).isFalse();

        Map<String, Object> edited = new HashMap<>(stored);
        edited.put("content", "{{ gone }} again");
        WriteContext edit = WriteContext.of("SmsTemplate", AccessType.UPDATE, edited,
                row("content", "{{ gone }} again"), stored);
        validator.validateUpdate(edit);
        assertThat(edit.hasErrors()).isTrue();
    }
}
