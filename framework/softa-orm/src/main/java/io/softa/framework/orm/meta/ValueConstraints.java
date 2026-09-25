package io.softa.framework.orm.meta;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public final class ValueConstraints {

    private ValueConstraints() {}

    /** How many distinct declarations each cache keeps; the least recently used one goes. */
    static final int MAX_CACHED = 512;

    /** Declared pattern text → compiled regex; an empty Optional marks text that does not compile. */
    private static final Map<String, Optional<Pattern>> PATTERNS = lru();
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
        Pattern pattern = compiled(c.pattern());
        if (pattern == null || matchesWithinBudget(pattern, value, metaField)) {
            return;
        }
        throw c.message() != null
                ? WriteValidationException.forField(metaField.getFieldName(), c.message())
                : WriteValidationException.forField(metaField.getFieldName(),
                        "Model field {0}:{1} is not in the required format: {2}.",
                        metaField.getModelName(), metaField.getFieldName(), value);
    }

    /** How many character reads one match may cost before the pattern is treated as unusable. */
    static final int MATCH_BUDGET = 200_000;

    /**
     * Whether the value matches, under a cap on how much work the match may do.
     *
     * <p>A regex is a declaration an admin can type in the studio, and it is then run against input an
     * ordinary user controls. Java's engine backtracks, and a nested-quantifier shape like
     * {@code (x+x+)+y} costs work that grows with the cube of the value's length — measured here at
     * 170 million character reads for 800 characters, a second of one thread, and eight times that for
     * every doubling. A {@code TEXT} field is long enough for that to matter, and {@code validate()}
     * compiles the regex without analysing it. No static analysis is reliable enough to be the only
     * guard either.
     *
     * <p>The cap is counted rather than timed — a wall clock makes the same write pass on an idle node
     * and fail on a busy one. A match that exceeds it is treated the way an uncompilable pattern is:
     * the rule is ignored and the write goes on to the rest of its checks. Refusing the value instead
     * would turn a bad declaration into a user's problem; this leaves it as an operator's, and says so
     * in the log. An ordinary pattern on an ordinary value costs a few reads per character.
     */
    private static boolean matchesWithinBudget(Pattern pattern, String value, MetaField metaField) {
        try {
            return pattern.matcher(new BudgetedCharSequence(value, MATCH_BUDGET)).matches();
        } catch (BudgetExceeded e) {
            log.error("Field pattern on {}.{} did not finish within {} character reads and is ignored"
                            + " for this write; the declared regular expression backtracks and needs rewriting: {}",
                    metaField.getModelName(), metaField.getFieldName(), MATCH_BUDGET, pattern.pattern());
            return true;
        }
    }

    /** Raised from inside the regex engine when a match has read more than it is allowed to. */
    private static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() {
            super(null, null, false, false);   // no message, no stack: it is control flow, not a report
        }
    }

    /**
     * The value as the regex engine sees it, counting every character it reads. Backtracking shows up
     * as re-reading, which is the whole of what makes a catastrophic pattern expensive, so counting
     * reads bounds the work without depending on the clock.
     */
    private record BudgetedCharSequence(CharSequence text, int[] remaining) implements CharSequence {

        BudgetedCharSequence(CharSequence text, int budget) {
            this(text, new int[] {budget});
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if (--remaining[0] < 0) {
                throw new BudgetExceeded();
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BudgetedCharSequence(text.subSequence(start, end), remaining);
        }

        @Override
        public String toString() {
            return text.toString();
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

    /** The declared pattern compiled, or null when it does not compile. */
    private static Pattern compiled(String declared) {
        return cached(PATTERNS, declared, text -> {
            try {
                return Optional.of(Pattern.compile(text));
            } catch (PatternSyntaxException e) {
                // Same reasoning as an unparseable bound below: rejected at scan time and dropped at
                // catalog load, so a regex that reaches here was written straight into sys_field. It is
                // ignored rather than allowed to fail every write to the model — and remembered as
                // ignored, or a throwing mapper would leave the cache empty and recompile each time.
                return Optional.empty();
            }
        }).orElse(null);
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
