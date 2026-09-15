package io.softa.framework.orm.meta;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.orm.service.validation.WriteValidationException;

/**
 * Enforces a field's declared value domain — {@code constraints.min / max / pattern}.
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
 * <p>Compiled patterns and parsed bounds are cached by their declared text, not by field: the
 * mapping from a regex string to its {@link Pattern} never changes, so a catalog reload needs no
 * invalidation — a redeclared field simply keys a new entry. Nothing removes an entry either, and a
 * studio that lets an admin retype a rule produces a new key every time, so each cache is a small LRU:
 * the declarations actually being written with stay compiled, and a churn of one-off texts evicts
 * itself instead of either growing without bound or — as a plain cap would — filling up once and
 * leaving every later write to recompile forever.
 *
 * <p>The message a field declares is shown as written; only the composed fallbacks are
 * {@code MessageFormat} patterns.
 */
public final class ValueConstraints {

    private ValueConstraints() {}

    /** How many distinct declarations each cache keeps; the least recently used one goes. */
    static final int MAX_CACHED = 512;

    private static final Map<String, Pattern> PATTERNS = lru();
    /** Declared bound text → parsed value; an empty Optional marks text that does not parse. */
    private static final Map<String, Optional<BigDecimal>> BOUNDS = lru();

    private static <T> Map<String, T> lru() {
        return new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, T> eldest) {
                return size() > MAX_CACHED;
            }
        };
    }

    /**
     * The cached value, computed on a miss. Synchronized because an access-ordered
     * {@link LinkedHashMap} reorders itself on a read, so even lookups mutate it.
     */
    private static <T> T cached(Map<String, T> cache, String key, java.util.function.Function<String, T> compute) {
        synchronized (cache) {
            return cache.computeIfAbsent(key, compute);
        }
    }

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
        FieldConstraints c = metaField.getConstraints();
        if (value == null || c == null || (c.min() == null && c.max() == null)) {
            return;
        }
        BigDecimal actual = toDecimal(value);
        if (actual == null) {
            // Not a number we can compare — the type processor's own conversion is the authority on
            // that, and it has either already thrown or accepted the value for its own reasons.
            return;
        }
        BigDecimal min = bound(c.min());
        BigDecimal max = bound(c.max());
        boolean belowMin = min != null && actual.compareTo(min) < 0;
        boolean aboveMax = max != null && actual.compareTo(max) > 0;
        if (belowMin || aboveMax) {
            throw c.message() != null
                    ? WriteValidationException.forField(metaField.getFieldName(), c.message())
                    : WriteValidationException.forField(metaField.getFieldName(), rangeMessage(c),
                            metaField.getModelName(), metaField.getFieldName(),
                            c.min(), c.max(), String.valueOf(value));
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
        FieldConstraints c = metaField.getConstraints();
        if (StringUtils.isBlank(value) || c == null || c.pattern() == null) {
            return;
        }
        if (!cached(PATTERNS, c.pattern(), Pattern::compile).matcher(value).matches()) {
            throw c.message() != null
                    ? WriteValidationException.forField(metaField.getFieldName(), c.message())
                    : WriteValidationException.forField(metaField.getFieldName(),
                            "Model field {0}:{1} is not in the required format: {2}.",
                            metaField.getModelName(), metaField.getFieldName(), value);
        }
    }

    /**
     * The composed sentence for a rejected bound, used when the field declares none.
     *
     * <p>Composing works here because a bound describes itself — "must be at least 0" needs nothing
     * a reader does not already have. It does not work for a pattern, which is why
     * {@code @Field(pattern)} asks for a message.
     */
    private static String rangeMessage(FieldConstraints c) {
        if (c.min() != null && c.max() != null) {
            return "Model field {0}:{1} must be between {2} and {3}, but the value is {4}.";
        }
        return c.min() != null
                ? "Model field {0}:{1} must be at least {2}, but the value is {4}."
                : "Model field {0}:{1} must be at most {3}, but the value is {4}.";
    }

    /** The declared bound, or null when it is absent or unparseable. */
    private static BigDecimal bound(String declared) {
        if (declared == null) {
            return null;
        }
        return cached(BOUNDS, declared, text -> {
            try {
                return Optional.of(new BigDecimal(text));
            } catch (NumberFormatException e) {
                // Rejected at scan time and dropped at catalog load, so reaching here means a row
                // written straight into sys_field. Ignoring it keeps one bad catalog row from failing
                // every write to the model; the drift audit is the channel for the row itself.
                return Optional.empty();
            }
        }).orElse(null);

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
