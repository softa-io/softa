package io.softa.framework.orm.meta;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.IllegalArgumentException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The declared value domain, enforced.
 *
 * <p>Two properties matter more than the arithmetic. A bound must not swallow absence — an optional
 * field with a minimum still has to be leavable empty, and conflating "no value" with "a value
 * below the floor" makes that impossible. And a bound must be exact: the whole reason the
 * declaration is a decimal literal rather than a double is that {@code BigDecimal} money fields
 * exist, and a bound that rounds is a bound that lets the wrong value through.
 */
class ValueConstraintsTest {

    private static MetaField numeric(String min, String max, String message) {
        MetaField field = new MetaField();
        field.setModelName("Department");
        field.setFieldName("activeEmpCount");
        field.setMin(min);
        field.setMax(max);
        field.setConstraintMessage(message);
        return field;
    }

    private static MetaField text(String pattern, String message) {
        MetaField field = new MetaField();
        field.setModelName("Employee");
        field.setFieldName("code");
        field.setPattern(pattern);
        field.setConstraintMessage(message);
        return field;
    }

    @Test
    void aValueBelowTheFloorIsRejected() {
        assertThatThrownBy(() -> ValueConstraints.checkRange(numeric("0", null, null), -4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activeEmpCount")
                .hasMessageContaining("at least 0");
    }

    @Test
    void theBoundsThemselvesPass() {
        // Inclusive on both ends. "between 0 and 100" reading as 1..99 is the kind of thing nobody
        // states and everybody assumes the other way.
        assertThatCode(() -> {
            ValueConstraints.checkRange(numeric("0", "100", null), 0);
            ValueConstraints.checkRange(numeric("0", "100", null), 100);
        }).doesNotThrowAnyException();
    }

    @Test
    void nullIsAbsenceAndNotAViolation() {
        // What `required` is for. A bounded optional field has to stay leavable empty.
        assertThatCode(() -> ValueConstraints.checkRange(numeric("0", "100", null), null))
                .doesNotThrowAnyException();
    }

    @Test
    void anUndeclaredDomainChecksNothing() {
        assertThatCode(() -> ValueConstraints.checkRange(numeric(null, null, null), -999))
                .doesNotThrowAnyException();
    }

    @Test
    void theBoundIsExactRatherThanRounded() {
        // The reason min/max are decimal literals and not doubles. Declared as `0.01d` this bound
        // would arrive as 0.010000000000000000208…, and 0.01 would fail its own minimum.
        MetaField price = numeric("0.01", null, null);

        assertThatCode(() -> ValueConstraints.checkRange(price, new BigDecimal("0.01")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ValueConstraints.checkRange(price, new BigDecimal("0.009")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyNumericShapeTheProcessorMayHandOverIsComparable() {
        // NumericProcessor coerces before calling, but which type it lands on depends on the field
        // type and on what the caller sent — Integer, Long, Double and BigDecimal all reach here.
        MetaField nonNegative = numeric("0", null, null);
        for (Object below : new Object[] {-1, -1L, -1.0d, new BigDecimal("-1"), "-1"}) {
            assertThatThrownBy(() -> ValueConstraints.checkRange(nonNegative, below))
                    .as("rejects %s", below.getClass().getSimpleName())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aDeclaredMessageReplacesTheComposedOne() {
        assertThatThrownBy(() -> ValueConstraints.checkRange(
                numeric("0", null, "Headcount cannot be negative."), -1))
                .hasMessageContaining("Headcount cannot be negative.");
    }

    @Test
    void aPatternMatchesTheWholeValueNotAPartOfIt() {
        // `find()` semantics would accept "XX123456-junk" against the same pattern. A format is a
        // statement about the whole value.
        MetaField code = text("[A-Z]{2}\\d{6}", "Employee code must be two letters and six digits.");

        assertThatCode(() -> ValueConstraints.checkPattern(code, "UN000123")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ValueConstraints.checkPattern(code, "UN000123-junk"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Employee code must be two letters and six digits.");
    }

    @Test
    void aBlankValueIsNotMatchedAgainstThePattern() {
        // Same reason as the numeric null: emptiness is `required`'s business, and a pattern that
        // rejects "" would make every optional formatted field mandatory by accident.
        MetaField code = text("[A-Z]{2}\\d{6}", null);
        assertThatCode(() -> {
            ValueConstraints.checkPattern(code, null);
            ValueConstraints.checkPattern(code, "   ");
        }).doesNotThrowAnyException();
    }

    @Test
    void aPatternWithNoMessageStillSaysSomethingUseful() {
        // Discouraged by the annotation's javadoc, but if it happens the sentence must not be the
        // regex itself — that tells the person filling the form nothing.
        assertThatThrownBy(() -> ValueConstraints.checkPattern(text("[A-Z]{2}", null), "abc"))
                .hasMessageContaining("not in the required format");
    }

    @Test
    void aCatalogRowWithAnUnparseableBoundDoesNotFailEveryWrite() {
        // AnnotationParser rejects this at scan time, so it can only arrive as a hand-written
        // sys_field row. One bad row should not stop the model being written to.
        assertThatCode(() -> ValueConstraints.checkRange(numeric("not-a-number", null, null), -5))
                .doesNotThrowAnyException();
    }
}
