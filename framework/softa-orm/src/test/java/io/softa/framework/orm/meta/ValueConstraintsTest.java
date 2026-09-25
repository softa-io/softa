package io.softa.framework.orm.meta;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.service.validation.WriteValidationException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The declared value domain — {@code min} / {@code max} / {@code pattern} — and what it does with a
 * declaration it cannot use. A catalog row written by hand is the only way an unusable one gets here,
 * and it must cost that one rule, not every write to the model.
 */
class ValueConstraintsTest {

    private static MetaField field(String name, FieldConstraints constraints) {
        MetaField f = new MetaField();
        ReflectionTestUtils.setField(f, "modelName", "EmpChangeRequest");
        ReflectionTestUtils.setField(f, "fieldName", name);
        ReflectionTestUtils.setField(f, "constraints", constraints);
        return f;
    }

    private static FieldConstraints c(String min, String max, String pattern, String message) {
        return FieldConstraints.of(min, max, pattern, message, null, null, null, "EmpChangeRequest.x");
    }

    @Test
    void aBoundRejectsOnlyWhatFallsOutsideIt() {
        MetaField amount = field("amount", c("0", "100", null, null));
        assertThatThrownBy(() -> ValueConstraints.checkRange(amount, new java.math.BigDecimal("-1")))
                .isInstanceOf(WriteValidationException.class).hasMessageContaining("must be between 0 and 100");
        assertThatThrownBy(() -> ValueConstraints.checkRange(amount, new java.math.BigDecimal("101")))
                .isInstanceOf(WriteValidationException.class);
        assertThatCode(() -> ValueConstraints.checkRange(amount, new java.math.BigDecimal("0"))).doesNotThrowAnyException();
        // Absence is what `required` is for: a bounded field must still be leavable empty.
        assertThatCode(() -> ValueConstraints.checkRange(amount, null)).doesNotThrowAnyException();

        // One bound alone names itself — that is why a bound composes its own sentence and a pattern
        // cannot.
        MetaField floor = field("lateMinutes", c("0", null, null, null));
        assertThatThrownBy(() -> ValueConstraints.checkRange(floor, -1))
                .isInstanceOf(WriteValidationException.class).hasMessageContaining("must be at least 0");
    }

    @Test
    void aPatternMatchesTheWholeValueAndIsSkippedForAnEmptyOne() {
        MetaField code = field("code", c(null, null, "[A-Z]{2}", "Country code must be two capital letters."));
        assertThatCode(() -> ValueConstraints.checkPattern(code, "SG")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ValueConstraints.checkPattern(code, "SGP"))
                .isInstanceOf(WriteValidationException.class)
                .hasMessageContaining("Country code must be two capital letters.");
        assertThatCode(() -> ValueConstraints.checkPattern(code, "")).doesNotThrowAnyException();
    }

    @Test
    void aPatternThatDoesNotCompileIsIgnoredInsteadOfFailingEveryWrite() {
        // Scan time rejects it and the catalog load drops it, so a regex that gets this far was written
        // straight into sys_field. An unparseable bound is already ignored; this must cost no more.
        MetaField code = field("code", c(null, null, "[A-Z", null));
        assertThatCode(() -> ValueConstraints.checkPattern(code, "anything")).doesNotThrowAnyException();
        // and again, from the cache — a throwing mapper would leave nothing remembered
        assertThatCode(() -> ValueConstraints.checkPattern(code, "anything else")).doesNotThrowAnyException();
    }

    @Test
    void aBoundThatDoesNotParseIsIgnoredTheSameWay() {
        MetaField amount = field("amount", c("not-a-number", null, null, null));
        assertThatCode(() -> ValueConstraints.checkRange(amount, new java.math.BigDecimal("-99"))).doesNotThrowAnyException();
    }

    @Test
    void aPatternThatBacktracksIsCutOffInsteadOfHoldingTheThread() {
        // A regex is typed by an admin in the studio and then run against input an ordinary user
        // controls. `(x+x+)+y` costs work growing with the cube of the value's length: 150 characters
        // already pass the cap, 800 cost a second of one thread, and every doubling costs eight times
        // more. The rule is dropped for that write rather than the value refused — a declaration that
        // cannot be run is an operator's problem, not the problem of whoever is filling in the form.
        MetaField code = field("code", c(null, null, "(x+x+)+y", null));
        String value = "x".repeat(150) + "!";
        assertThatCode(() -> ValueConstraints.checkPattern(code, value)).doesNotThrowAnyException();
        // and a value short enough to finish inside the cap is still judged
        assertThatThrownBy(() -> ValueConstraints.checkPattern(code, "xx!"))
                .isInstanceOf(WriteValidationException.class);

        // an ordinary pattern is nowhere near the cap, on a value far longer than that one
        MetaField country = field("country", c(null, null, "[A-Z]{2}", null));
        assertThatCode(() -> ValueConstraints.checkPattern(country, "SG")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ValueConstraints.checkPattern(country, "A".repeat(10_000)))
                .isInstanceOf(WriteValidationException.class);
    }
}
