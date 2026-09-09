package io.softa.framework.orm.meta;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.exception.IllegalArgumentException;

/**
 * Enforces a field's declared value domain — {@code @Field(min / max / pattern)}.
 *
 * <p>Lives beside {@link MetaField} rather than inside a processor because the declaration is a
 * property of the field, not of one write path. Every write already funnels through the
 * field-processor pipeline (create, update, batch, import, seed loading, flow write nodes), so
 * calling this from the numeric and string processors covers all of them; a reader that wants the
 * same answer without writing — the filter builder validating what an admin typed, say — can ask
 * here too instead of restating the rule.
 *
 * <p>What it is not: a column constraint. No CHECK is rendered and no DDL changes, so the bound can
 * be tightened or relaxed by redeploying rather than by migrating a table. The trade is that data
 * written before a bound existed is not retroactively invalid — which is the behaviour you want
 * from a business rule and would not get from a CHECK.
 *
 * <p>The patterns are compiled per call rather than cached. {@link Pattern#matches} does the same
 * thing internally, the JDK caches nothing either way, and a per-field cache here would have to be
 * invalidated when {@code ModelManager} reloads its catalog — a correctness risk for a cost that
 * does not show up next to the surrounding JDBC round trip.
 */
public final class ValueConstraints {

    private ValueConstraints() {}

    /**
     * Rejects a numeric value outside the field's declared bounds.
     *
     * <p>Null passes: absence is what {@code required} is for, and conflating the two would make an
     * optional bounded field impossible to leave empty.
     *
     * @param metaField the field being written
     * @param value the incoming value, already coerced to its numeric type
     */
    public static void checkRange(MetaField metaField, Object value) {
        if (value == null || (metaField.getMin() == null && metaField.getMax() == null)) {
            return;
        }
        BigDecimal actual = toDecimal(value);
        if (actual == null) {
            // Not a number we can compare — the type processor's own conversion is the authority on
            // that, and it has either already thrown or accepted the value for its own reasons.
            return;
        }
        BigDecimal min = bound(metaField.getMin());
        BigDecimal max = bound(metaField.getMax());
        boolean belowMin = min != null && actual.compareTo(min) < 0;
        boolean aboveMax = max != null && actual.compareTo(max) > 0;
        if (belowMin || aboveMax) {
            throw new IllegalArgumentException(rangeMessage(metaField),
                    metaField.getModelName(), metaField.getFieldName(),
                    metaField.getMin(), metaField.getMax(), String.valueOf(value));
        }
    }

    /**
     * Rejects a string that does not match the field's declared pattern.
     *
     * <p>Whole-value match, not a search: {@code "^[A-Z]{2}$"} and {@code "[A-Z]{2}"} should mean
     * the same thing on a field's format, and only one of them does under {@code find()}.
     *
     * @param metaField the field being written
     * @param value the incoming value, already trimmed
     */
    public static void checkPattern(MetaField metaField, String value) {
        if (StringUtils.isBlank(value) || metaField.getPattern() == null) {
            return;
        }
        if (!Pattern.matches(metaField.getPattern(), value)) {
            throw new IllegalArgumentException(patternMessage(metaField),
                    metaField.getModelName(), metaField.getFieldName(), value);
        }
    }

    /**
     * The sentence for a rejected bound: the field's own if it has one, otherwise composed.
     *
     * <p>Composing works here because a bound describes itself — "must be at least 0" needs nothing
     * a reader does not already have. It does not work for a pattern, which is why
     * {@code @Field(pattern)} asks for a message.
     */
    private static String rangeMessage(MetaField metaField) {
        if (metaField.getConstraintMessage() != null) {
            return metaField.getConstraintMessage();
        }
        if (metaField.getMin() != null && metaField.getMax() != null) {
            return "Model field {0}:{1} must be between {2} and {3}, but the value is {4}.";
        }
        return metaField.getMin() != null
                ? "Model field {0}:{1} must be at least {2}, but the value is {4}."
                : "Model field {0}:{1} must be at most {3}, but the value is {4}.";
    }

    private static String patternMessage(MetaField metaField) {
        return metaField.getConstraintMessage() != null
                ? metaField.getConstraintMessage()
                : "Model field {0}:{1} is not in the required format: {2}.";
    }

    /** The declared bound, or null when it is absent or unparseable. */
    private static BigDecimal bound(String declared) {
        if (declared == null) {
            return null;
        }
        try {
            return new BigDecimal(declared);
        } catch (NumberFormatException e) {
            // AnnotationParser rejects a malformed bound at scan time, so reaching here means a row
            // written straight into sys_field. Ignoring it keeps one bad catalog row from failing
            // every write to the model; the drift audit is the channel for the row itself.
            return null;
        }
    }

    private static BigDecimal toDecimal(Object value) {
        return switch (value) {
            case BigDecimal decimal -> decimal;
            case Integer intValue -> BigDecimal.valueOf(intValue);
            case Long longValue -> BigDecimal.valueOf(longValue);
            case Double doubleValue -> BigDecimal.valueOf(doubleValue);
            case Number number -> new BigDecimal(number.toString());
            case String text -> {
                try {
                    yield new BigDecimal(text.trim());
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case null, default -> null;
        };
    }
}
