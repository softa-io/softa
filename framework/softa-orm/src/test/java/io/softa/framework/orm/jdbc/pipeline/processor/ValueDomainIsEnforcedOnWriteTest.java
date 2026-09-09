package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That the pipeline actually asks.
 *
 * <p>{@code ValueConstraintsTest} settles what the rule decides. It cannot settle whether anything
 * consults it — and that is the half worth pinning, because every write in the system reaches the
 * database through these processors and nothing else would notice their absence. A declaration that
 * is parsed, stored, shipped in the metadata and never consulted looks completely healthy from
 * every angle except the one that matters.
 *
 * <p>Driving the processors directly also fixes the two orderings the rule depends on: the bound is
 * compared after the numeric coercion (so {@code "5"} is judged as 5, not as a String), and the
 * pattern is matched after the trim (so a pasted trailing space is not a format error).
 */
class ValueDomainIsEnforcedOnWriteTest {

    /**
     * MetaField's setters are package-private on purpose — it is a read-only view of the catalog and
     * only {@code io.softa.framework.orm.meta} may build one. Reflection is how the other processor
     * tests do it (see {@code EncryptedProcessorTest}), rather than widening that boundary for tests.
     */
    private static MetaField field(FieldType fieldType, String fieldName) {
        MetaField metaField = new MetaField();
        set(metaField, "modelName", "Department");
        set(metaField, "fieldName", fieldName);
        set(metaField, "fieldType", fieldType);
        return metaField;
    }

    private static void set(MetaField metaField, String property, Object value) {
        ReflectionTestUtils.setField(metaField, property, value);
    }

    private static Map<String, Object> row(String fieldName, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(fieldName, value);
        return row;
    }

    @Test
    void aNumericWriteBelowTheFloorIsRejectedByTheProcessor() {
        MetaField headcount = field(FieldType.INTEGER, "activeEmpCount");
        set(headcount, "min", "0");
        NumericProcessor processor = new NumericProcessor(headcount, AccessType.UPDATE);

        assertThatThrownBy(() -> processor.processInputRow(row("activeEmpCount", -4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activeEmpCount");
    }

    @Test
    void theBoundIsCheckedAfterTheTypeCoercionNotBeforeIt() {
        // A caller may send "5" for a LONG field; formatInputNumeric turns it into 5L. Checking the
        // raw String would compare text against a number and silently pass anything.
        MetaField headcount = field(FieldType.LONG, "activeEmpCount");
        set(headcount, "min", "0");
        NumericProcessor processor = new NumericProcessor(headcount, AccessType.UPDATE);

        assertThatThrownBy(() -> processor.processInputRow(row("activeEmpCount", "-4")))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> valid = row("activeEmpCount", "5");
        assertThatCode(() -> processor.processInputRow(valid)).doesNotThrowAnyException();
        assertThat(valid).as("and the coerced value is what lands in the row")
                .containsEntry("activeEmpCount", 5L);
    }

    @Test
    void aNumericWriteWithNoDeclaredDomainIsUntouched() {
        MetaField plain = field(FieldType.INTEGER, "sequence");
        NumericProcessor processor = new NumericProcessor(plain, AccessType.UPDATE);

        Map<String, Object> negative = row("sequence", -999);
        assertThatCode(() -> processor.processInputRow(negative)).doesNotThrowAnyException();
        assertThat(negative).containsEntry("sequence", -999);
    }

    @Test
    void aStringWriteThatBreaksTheFormatIsRejectedByTheProcessor() {
        MetaField code = field(FieldType.STRING, "code");
        set(code, "pattern", "[A-Z]{2}\\d{6}");
        set(code, "constraintMessage", "Employee code must be two letters and six digits.");
        StringProcessor processor = new StringProcessor(code, AccessType.CREATE);

        assertThatThrownBy(() -> processor.processInputRow(row("code", "abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Employee code must be two letters and six digits.");
    }

    @Test
    void thePatternIsMatchedAfterTheTrim() {
        // The processor trims before validating, and it must stay that way: an anchored pattern
        // would otherwise reject a value someone pasted out of a spreadsheet cell.
        MetaField code = field(FieldType.STRING, "code");
        set(code, "pattern", "[A-Z]{2}\\d{6}");
        StringProcessor processor = new StringProcessor(code, AccessType.CREATE);

        Map<String, Object> padded = row("code", "  UN000123  ");
        assertThatCode(() -> processor.processInputRow(padded)).doesNotThrowAnyException();
        assertThat(padded).containsEntry("code", "UN000123");
    }

    @Test
    void anEmptyOptionalStringIsNotAFormatError() {
        // Emptiness is `required`'s business. A pattern that rejects "" would quietly make every
        // formatted field mandatory.
        MetaField code = field(FieldType.STRING, "code");
        set(code, "pattern", "[A-Z]{2}\\d{6}");
        StringProcessor processor = new StringProcessor(code, AccessType.UPDATE);

        assertThatCode(() -> processor.processInputRow(row("code", ""))).doesNotThrowAnyException();
    }
}
