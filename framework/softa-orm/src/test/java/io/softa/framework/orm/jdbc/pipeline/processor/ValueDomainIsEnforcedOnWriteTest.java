package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.FieldConstraints;
import io.softa.framework.orm.meta.MetaField;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That the pipeline actually asks.
 *
 * <p>{@code FieldConstraintsTest} settles what the declaration means. It cannot settle whether
 * anything consults it — and that is the half worth pinning, because every write in the system
 * reaches the database through these processors and nothing else would notice their absence.
 *
 * <p>Driving the processors directly also fixes the two orderings the rule depends on: the bound is
 * compared after the numeric coercion (so {@code "5"} is judged as 5, not as a String), and the
 * pattern is matched after the trim (so a pasted trailing space is not a format error).
 */
class ValueDomainIsEnforcedOnWriteTest {

    private static MetaField field(FieldType fieldType, String fieldName, FieldConstraints constraints) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", "Department");
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "fieldType", fieldType);
        ReflectionTestUtils.setField(metaField, "constraints", constraints);
        return metaField;
    }

    private static FieldConstraints domain(String min, String max, String pattern, String message) {
        return FieldConstraints.of(min, max, pattern, message, null, null, null, null, "Department.x");
    }

    private static Map<String, Object> row(String fieldName, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(fieldName, value);
        return row;
    }

    @Test
    void aNumericWriteBelowTheFloorIsRejectedByTheProcessor() {
        NumericProcessor processor = new NumericProcessor(
                field(FieldType.INTEGER, "activeEmpCount", domain("0", null, null, null)), AccessType.UPDATE);
        assertThatThrownBy(() -> processor.processInputRow(row("activeEmpCount", -4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activeEmpCount");
    }

    @Test
    void theBoundIsCheckedAfterTheTypeCoercionNotBeforeIt() {
        NumericProcessor processor = new NumericProcessor(
                field(FieldType.LONG, "activeEmpCount", domain("0", null, null, null)), AccessType.UPDATE);
        assertThatThrownBy(() -> processor.processInputRow(row("activeEmpCount", "-4")))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> valid = row("activeEmpCount", "5");
        assertThatCode(() -> processor.processInputRow(valid)).doesNotThrowAnyException();
        assertThat(valid).containsEntry("activeEmpCount", 5L);
    }

    @Test
    void aNumericWriteWithNoDeclaredDomainIsUntouched() {
        NumericProcessor processor = new NumericProcessor(field(FieldType.INTEGER, "sequence", null), AccessType.UPDATE);
        Map<String, Object> negative = row("sequence", -999);
        assertThatCode(() -> processor.processInputRow(negative)).doesNotThrowAnyException();
        assertThat(negative).containsEntry("sequence", -999);
    }

    @Test
    void aStringWriteThatBreaksTheFormatIsRejectedWithTheDeclaredMessage() {
        StringProcessor processor = new StringProcessor(field(FieldType.STRING, "code",
                domain(null, null, "[A-Z]{2}\\d{6}", "Employee code must be two letters and six digits.")), AccessType.CREATE);
        assertThatThrownBy(() -> processor.processInputRow(row("code", "abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Employee code must be two letters and six digits.");
    }

    @Test
    void aDeclaredMessageWithBracesOrAnApostropheIsShownAsWritten() {
        StringProcessor processor = new StringProcessor(field(FieldType.STRING, "code",
                domain(null, null, "[A-Z]{2}\\d{6}", "Use the form {CC}{NNNNNN}, e.g. SG000123; it's the employee's code.")), AccessType.CREATE);
        assertThatThrownBy(() -> processor.processInputRow(row("code", "abc")))
                .hasMessage("Use the form {CC}{NNNNNN}, e.g. SG000123; it's the employee's code.");
        NumericProcessor bounded = new NumericProcessor(field(FieldType.INTEGER, "headcount",
                domain("0", null, null, "Headcount can't be negative {ever}.")), AccessType.CREATE);
        assertThatThrownBy(() -> bounded.processInputRow(row("headcount", -1)))
                .hasMessage("Headcount can't be negative {ever}.");
    }

    @Test
    void thePatternIsMatchedAfterTheTrimAndAnEmptyOptionalStringPasses() {
        StringProcessor processor = new StringProcessor(field(FieldType.STRING, "code",
                domain(null, null, "[A-Z]{2}\\d{6}", null)), AccessType.UPDATE);
        Map<String, Object> padded = row("code", "  UN000123  ");
        assertThatCode(() -> processor.processInputRow(padded)).doesNotThrowAnyException();
        assertThat(padded).containsEntry("code", "UN000123");
        // Emptiness is `required`'s business.
        assertThatCode(() -> processor.processInputRow(row("code", ""))).doesNotThrowAnyException();
    }
}
