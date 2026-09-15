package io.softa.framework.orm.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import io.softa.framework.base.constant.EnvConstant;
import io.softa.framework.base.constant.TimeConstant;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.FilterType;
import io.softa.framework.orm.enums.LogicOperator;

/**
 * Evaluates a {@link Filters} tree against one row held in memory — the counterpart of
 * {@code FilterUnitParser}, which compiles the same tree into SQL.
 *
 * <p>Exists for the field constraints ({@code requiredWhen} / {@code hiddenWhen} / {@code readonlyWhen}
 * / {@code invalidWhen}): the row being written is in hand and has not been stored yet, so asking the
 * database is not an option, and the frontend evaluates the identical expression against the form
 * values with its own evaluator. The two evaluators must agree, so the semantics below are written
 * down rather than inherited from SQL where the two differ:
 *
 * <ul>
 *   <li><b>null and the empty string are the same value.</b> A form clears a field to {@code ""},
 *       the database stores {@code null}; {@code ["reason", "=", "Others"]} must answer the same for
 *       both. Equality is value equality — {@code ["country", "!=", "SG"]} is <i>true</i> for a row
 *       with no country, where SQL would say unknown.</li>
 *   <li><b>Ordering needs two values.</b> {@code <}, {@code >}, {@code BETWEEN} are false when the
 *       field is empty; their negations ({@code NOT BETWEEN}) are true — a negation always answers
 *       the opposite of what it negates.</li>
 *   <li><b>Values are coerced by the field's type</b>, not by their Java class: an original row read
 *       from the database carries dates as strings, a patch carries {@code LocalDate}, and the two
 *       must compare equal. Numbers compare as {@code BigDecimal}; options by item code; relations by
 *       id rendered as text (a code-as-id relation is text already).</li>
 *   <li><b>A multi-value field is a set.</b> {@code =} / {@code IN} ask whether the set contains the
 *       value(s); {@code IS SET} asks whether it is non-empty.</li>
 *   <li>{@code PARENT OF} / {@code CHILD OF} need a query and are refused — the declaration is
 *       rejected at boot before it can reach here.</li>
 * </ul>
 *
 * <p>Values may be placeholders. {@code {{ @startDate }}} reads another field of the same row;
 * {@code {{ TODAY }}} / {@code {{ NOW }}} / {@code {{ YESTERDAY }}} / {@code {{ USER_ID }}} read the
 * {@link EvalContext}; both accept an ISO-8601 offset — {@code {{ TODAY - P13Y }}},
 * {@code {{ @hireDate + P6M }}}, {@code {{ NOW - PT2H }}}. In the field slot, {@code @mode} and
 * {@code @userId} name the context rather than the row.
 */
public final class FilterEvaluator {

    /** The reserved names a condition may use in the field slot, read from the context, not the row. */
    public static final String MODE_VARIABLE = "@mode";
    public static final String USER_ID_VARIABLE = "@userId";
    public static final Set<String> RESERVED_VARIABLES = Set.of(MODE_VARIABLE, USER_ID_VARIABLE);

    /** Environment tokens resolvable without a database or an employee record. */
    public static final Set<String> ENV_TOKENS =
            Set.of(EnvConstant.USER_ID, EnvConstant.TODAY, EnvConstant.YESTERDAY, EnvConstant.NOW);

    /** Tokens that resolve to a calendar day (an offset must then be a {@link Period}). */
    public static final Set<String> DATE_TOKENS = Set.of(EnvConstant.TODAY, EnvConstant.YESTERDAY);

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "^\\{\\{\\s*(@?[A-Za-z_][A-Za-z0-9_.]*)\\s*(?:([+-])\\s*(P[0-9A-Z.]+))?\\s*}}$");

    private FilterEvaluator() {}

    /**
     * How a placeholder value resolves. {@code LITERAL} is "not a placeholder — use the value as is".
     */
    public enum RefKind { LITERAL, FIELD, ENV }

    /**
     * A parsed value placeholder.
     *
     * @param kind field reference, environment token, or a plain literal
     * @param name the field name or token (without the {@code @}); null for a literal
     * @param offset the ISO-8601 amount to add, or null
     * @param negative whether the offset is subtracted
     */
    public record ValueRef(RefKind kind, @Nullable String name, @Nullable TemporalAmount offset, boolean negative) {

        static final ValueRef LITERAL = new ValueRef(RefKind.LITERAL, null, null, false);

        public boolean isReference() {
            return kind != RefKind.LITERAL;
        }

        /** Whether the offset is a {@link Duration} (hours / minutes), which a calendar day cannot absorb. */
        public boolean hasTimeOffset() {
            return offset instanceof Duration;
        }
    }

    /**
     * Classify a comparison value: another field, an environment token, or a literal.
     *
     * @throws IllegalArgumentException when the text is placeholder-shaped but the offset is not a
     *         parseable ISO-8601 amount — {@code {{ TODAY - 13Y }}} is a mistake, not a literal
     */
    public static ValueRef parseValueRef(@Nullable Object value) {
        if (!(value instanceof String text)) {
            return ValueRef.LITERAL;
        }
        Matcher m = PLACEHOLDER.matcher(text.trim());
        if (!m.matches()) {
            return ValueRef.LITERAL;
        }
        String name = m.group(1);
        TemporalAmount offset = null;
        if (m.group(3) != null) {
            offset = parseAmount(m.group(3), text);
        }
        boolean negative = "-".equals(m.group(2));
        if (name.startsWith("@")) {
            return new ValueRef(RefKind.FIELD, name.substring(1), offset, negative);
        }
        if (ENV_TOKENS.contains(name.toUpperCase())) {
            return new ValueRef(RefKind.ENV, name.toUpperCase(), offset, negative);
        }
        // `{{ SOMETHING_ELSE }}` — FilterUnitParser binds an unknown token as a literal string and so
        // does this; the boot-time validation is where an unknown token is reported.
        return ValueRef.LITERAL;
    }

    private static TemporalAmount parseAmount(String iso, String whole) {
        try {
            return iso.contains("T") ? Duration.parse(iso) : Period.parse(iso);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "The offset in " + whole + " is not an ISO-8601 amount (P13Y, P6M, PT2H): " + iso, e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Evaluation
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether {@code filters} holds for {@code row}.
     *
     * @param filters the condition; an empty or null condition holds (it constrains nothing)
     * @param row the row's raw values — merged with the stored row on update, as handed in on create
     * @param ctx reserved variables and environment tokens
     * @param typeOf resolves a field name of the row's model to its type, or null for a name it does
     *               not know; drives the coercion of both sides of a comparison
     */
    public static boolean matches(@Nullable Filters filters, Map<String, Object> row, EvalContext ctx,
                                  Function<String, @Nullable FieldType> typeOf) {
        if (filters == null || FilterType.EMPTY.equals(filters.getType())) {
            return true;
        }
        if (FilterType.TREE.equals(filters.getType())) {
            boolean any = LogicOperator.OR.equals(filters.getLogicOperator());
            for (Filters child : filters.getChildren()) {
                boolean hit = matches(child, row, ctx, typeOf);
                if (any && hit) {
                    return true;
                }
                if (!any && !hit) {
                    return false;
                }
            }
            return !any;
        }
        return matchesUnit(filters.getFilterUnit(), row, ctx, typeOf);
    }

    private static boolean matchesUnit(FilterUnit unit, Map<String, Object> row, EvalContext ctx,
                                       Function<String, @Nullable FieldType> typeOf) {
        Operator op = unit.getOperator();
        if (unit.isTuple()) {
            return matchesTuple(unit, row, ctx, typeOf);
        }
        String field = unit.getField();
        Object left = leftValue(field, row, ctx);
        FieldType type = RESERVED_VARIABLES.contains(field) ? null : typeOf.apply(field);
        Object right = resolveValue(unit.getValue(), row, ctx);

        return switch (op) {
            case EQUAL -> equal(left, right, type);
            case NOT_EQUAL -> !equal(left, right, type);
            case GREATER_THAN -> compare(left, right, type, c -> c > 0);
            case GREATER_THAN_OR_EQUAL -> compare(left, right, type, c -> c >= 0);
            case LESS_THAN -> compare(left, right, type, c -> c < 0);
            case LESS_THAN_OR_EQUAL -> compare(left, right, type, c -> c <= 0);
            case CONTAINS -> text(left).contains(text(right));
            case NOT_CONTAINS -> !text(left).contains(text(right));
            case START_WITH -> text(left).startsWith(text(right));
            case NOT_START_WITH -> !text(left).startsWith(text(right));
            case IN -> in(left, right, type);
            case NOT_IN -> !in(left, right, type);
            case BETWEEN -> between(left, right, type);
            case NOT_BETWEEN -> !between(left, right, type);
            case IS_SET -> !isBlank(left);
            case IS_NOT_SET -> isBlank(left);
            case PARENT_OF, CHILD_OF -> throw new UnsupportedOperationException(
                    op.getName() + " needs a query and cannot be evaluated against a single row: " + unit);
        };
    }

    private static boolean matchesTuple(FilterUnit unit, Map<String, Object> row, EvalContext ctx,
                                        Function<String, @Nullable FieldType> typeOf) {
        List<String> fields = unit.getFields();
        Collection<?> tuples = (Collection<?>) unit.getValue();
        boolean hit = false;
        for (Object tupleObj : tuples) {
            List<?> tuple = new ArrayList<>((Collection<?>) tupleObj);
            boolean all = true;
            for (int i = 0; i < fields.size() && all; i++) {
                String field = fields.get(i);
                FieldType type = RESERVED_VARIABLES.contains(field) ? null : typeOf.apply(field);
                all = equal(leftValue(field, row, ctx), resolveValue(tuple.get(i), row, ctx), type);
            }
            if (all) {
                hit = true;
                break;
            }
        }
        return Operator.IN.equals(unit.getOperator()) == hit;
    }

    private static @Nullable Object leftValue(String field, Map<String, Object> row, EvalContext ctx) {
        if (MODE_VARIABLE.equals(field)) {
            return ctx.modeName();
        }
        if (USER_ID_VARIABLE.equals(field)) {
            return ctx.userId();
        }
        return row.get(field);
    }

    /** Resolve a comparison value: another field, an environment token (with offset), or the literal. */
    static @Nullable Object resolveValue(@Nullable Object value, Map<String, Object> row, EvalContext ctx) {
        if (value instanceof Collection<?> values) {
            List<Object> resolved = new ArrayList<>(values.size());
            values.forEach(v -> resolved.add(resolveValue(v, row, ctx)));
            return resolved;
        }
        ValueRef ref = parseValueRef(value);
        Object base = switch (ref.kind()) {
            case LITERAL -> value;
            case FIELD -> row.get(ref.name());
            case ENV -> switch (ref.name()) {
                case EnvConstant.USER_ID -> ctx.userId();
                case EnvConstant.TODAY -> ctx.today();
                case EnvConstant.YESTERDAY -> ctx.today() == null ? null : ctx.today().minusDays(1);
                case EnvConstant.NOW -> ctx.now();
                default -> value;
            };
        };
        return ref.offset() == null ? base : shift(base, ref);
    }

    private static @Nullable Object shift(@Nullable Object base, ValueRef ref) {
        if (base == null) {
            return null;
        }
        Temporal temporal;
        if (ref.hasTimeOffset()) {
            temporal = toDateTime(base);
        } else {
            temporal = base instanceof LocalDateTime || (base instanceof String s && s.length() > 10)
                    ? toDateTime(base) : toDate(base);
        }
        if (temporal == null) {
            return null;
        }
        return ref.negative() ? temporal.minus(ref.offset()) : temporal.plus(ref.offset());
    }

    // ---------------------------------------------------------------------------------------------
    // Comparison semantics
    // ---------------------------------------------------------------------------------------------

    /** null, a blank string and an empty collection are one and the same: "nothing there". */
    public static boolean isBlank(@Nullable Object value) {
        return value == null
                || (value instanceof String s && s.isBlank())
                || (value instanceof Collection<?> c && c.isEmpty());
    }

    /**
     * Value equality under the field's type: two empties are equal, a multi-value left side matches
     * when any element does, and two values that both coerce compare as {@code 0}. A value that does
     * not coerce ({@code "n/a"} on a DATE field) is equal to nothing — not even to another such value.
     */
    public static boolean equal(@Nullable Object left, @Nullable Object right, @Nullable FieldType type) {
        // Emptiness is decided before the set semantics: an empty multi-value field is "nothing there",
        // the same value as null and "", and asking an empty set for a member would answer no to both.
        boolean leftBlank = isBlank(left);
        boolean rightBlank = isBlank(right);
        if (leftBlank || rightBlank) {
            return leftBlank && rightBlank;
        }
        if (left instanceof Collection<?> set) {
            return set.stream().anyMatch(element -> equal(element, right, type));
        }
        Integer c = compareOrNull(left, right, type);
        return c != null && c == 0;
    }

    private static boolean compare(@Nullable Object left, @Nullable Object right, @Nullable FieldType type, IntPredicate verdict) {
        Integer c = compareOrNull(left, right, type);
        return c != null && verdict.test(c);
    }

    /** The ordering of two values, or null when either is empty or not comparable under the kind. */
    private static @Nullable Integer compareOrNull(@Nullable Object left, @Nullable Object right, @Nullable FieldType type) {
        if (isBlank(left) || isBlank(right) || left instanceof Collection<?>) {
            return null;
        }
        Kind kind = kindOf(type, left, right);
        return switch (kind) {
            case NUMBER -> {
                BigDecimal a = toNumber(left);
                BigDecimal b = toNumber(right);
                yield a == null || b == null ? null : a.compareTo(b);
            }
            case DATE -> {
                LocalDate a = toDate(left);
                LocalDate b = toDate(right);
                yield a == null || b == null ? null : a.compareTo(b);
            }
            case DATETIME -> {
                LocalDateTime a = toDateTime(left);
                LocalDateTime b = toDateTime(right);
                yield a == null || b == null ? null : a.compareTo(b);
            }
            case TIME -> {
                LocalTime a = toTime(left);
                LocalTime b = toTime(right);
                yield a == null || b == null ? null : a.compareTo(b);
            }
            case BOOLEAN -> {
                Boolean a = toBoolean(left);
                Boolean b = toBoolean(right);
                yield a == null || b == null ? null : a.compareTo(b);
            }
            case TEXT -> text(left).compareTo(text(right));
        };
    }

    private static boolean in(@Nullable Object left, @Nullable Object right, @Nullable FieldType type) {
        if (!(right instanceof Collection<?> candidates)) {
            return equal(left, right, type);
        }
        return candidates.stream().anyMatch(candidate -> equal(left, candidate, type));
    }

    private static boolean between(@Nullable Object left, @Nullable Object right, @Nullable FieldType type) {
        if (!(right instanceof Collection<?> bounds) || bounds.size() != 2) {
            return false;
        }
        List<?> pair = new ArrayList<>(bounds);
        Integer low = compareOrNull(left, pair.get(0), type);
        Integer high = compareOrNull(left, pair.get(1), type);
        return low != null && high != null && low >= 0 && high <= 0;
    }

    // ---------------------------------------------------------------------------------------------
    // Coercion
    // ---------------------------------------------------------------------------------------------

    private enum Kind { NUMBER, DATE, DATETIME, TIME, BOOLEAN, TEXT }

    /**
     * The comparison kind: the field's declared type when known, otherwise inferred from the values
     * (a reserved variable such as {@code @userId} has no metadata).
     */
    private static Kind kindOf(@Nullable FieldType type, Object left, Object right) {
        if (type != null) {
            if (FieldType.NUMERIC_TYPES.contains(type)) return Kind.NUMBER;
            return switch (type) {
                case DATE -> Kind.DATE;
                case DATE_TIME -> Kind.DATETIME;
                case TIME -> Kind.TIME;
                case BOOLEAN -> Kind.BOOLEAN;
                default -> Kind.TEXT;
            };
        }
        if (left instanceof Number && right instanceof Number) return Kind.NUMBER;
        if (left instanceof LocalDate || right instanceof LocalDate) return Kind.DATE;
        if (left instanceof LocalDateTime || right instanceof LocalDateTime) return Kind.DATETIME;
        if (left instanceof LocalTime || right instanceof LocalTime) return Kind.TIME;
        if (left instanceof Boolean && right instanceof Boolean) return Kind.BOOLEAN;
        return Kind.TEXT;
    }

    static @Nullable BigDecimal toNumber(Object value) {
        return switch (value) {
            case BigDecimal d -> d;
            case Number n -> new BigDecimal(n.toString());
            case String s -> {
                try {
                    yield new BigDecimal(s.trim());
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    static @Nullable LocalDate toDate(Object value) {
        if (value instanceof LocalDate d) {
            return d;
        }
        LocalDateTime dateTime = toDateTime(value);
        return dateTime == null ? null : dateTime.toLocalDate();
    }

    /**
     * A date-time from what a row may carry: the temporal types, {@code java.util.Date}, or text in
     * the framework's {@code yyyy-MM-dd HH:mm:ss} / {@code yyyy-MM-dd} shapes and in ISO-8601
     * ({@code 2024-01-31T09:30:00}, with or without a zone offset) — the pipeline's type cast accepts
     * both spellings, so a patch may arrive in either.
     */
    static @Nullable LocalDateTime toDateTime(Object value) {
        return switch (value) {
            case LocalDateTime dt -> dt;
            case LocalDate d -> d.atStartOfDay();
            // java.sql.Date and java.sql.Time refuse toInstant(), which is how DateUtils converts a
            // java.util.Date, so both must be answered before that arm: a calendar day starts its day,
            // and a clock time names no day at all. (java.sql.Timestamp converts fine and needs none.)
            case java.sql.Date d -> d.toLocalDate().atStartOfDay();
            case java.sql.Time ignored -> null;
            case java.util.Date d -> DateUtils.dateToLocalDateTime(d);
            case String s -> parseDateTime(s.trim());
            default -> null;
        };
    }

    private static @Nullable LocalDateTime parseDateTime(String text) {
        if (text.length() <= 10) {
            return parse(() -> LocalDate.parse(text, TimeConstant.DATE_FORMATTER).atStartOfDay());
        }
        LocalDateTime parsed = parse(() -> LocalDateTime.parse(text, TimeConstant.DATETIME_FORMATTER));
        if (parsed == null) {
            parsed = parse(() -> LocalDateTime.parse(text));
        }
        if (parsed == null) {
            // The local part of an offset spelling, not the instant re-zoned: every stored DATE_TIME is
            // zone-less wall clock (DateTimeProcessor refuses an offset outright), so only a rule's own
            // literal can carry one. Re-zoning it would move the rule relative to every value it is
            // compared against, and make one declaration answer differently per server timezone — and
            // differently again from the browser, which evaluates the same rule on the form.
            parsed = parse(() -> OffsetDateTime.parse(text).toLocalDateTime());
        }
        return parsed;
    }

    private static <T> @Nullable T parse(java.util.function.Supplier<T> parser) {
        try {
            return parser.get();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static @Nullable LocalTime toTime(Object value) {
        return switch (value) {
            case LocalTime t -> t;
            case LocalDateTime dt -> dt.toLocalTime();
            case java.sql.Time t -> t.toLocalTime();
            case String s -> {
                String t = s.trim();
                LocalTime parsed = parse(() -> LocalTime.parse(t, TimeConstant.TIME_FORMATTER));
                yield parsed != null ? parsed : parse(() -> LocalTime.parse(t));
            }
            default -> null;
        };
    }

    static @Nullable Boolean toBoolean(Object value) {
        return switch (value) {
            case Boolean b -> b;
            case Number n -> n.intValue() != 0;
            case String s -> "true".equalsIgnoreCase(s.trim()) || "1".equals(s.trim())
                    ? Boolean.TRUE
                    : ("false".equalsIgnoreCase(s.trim()) || "0".equals(s.trim()) ? Boolean.FALSE : null);
            default -> null;
        };
    }

    /** Text form for string operators; empty for "nothing there", so {@code CONTAINS} never NPEs. */
    static String text(@Nullable Object value) {
        if (isBlank(value)) {
            return "";
        }
        if (value instanceof LocalDate d) {
            return TimeConstant.DATE_FORMATTER.format(d);
        }
        if (value instanceof LocalDateTime dt) {
            return TimeConstant.DATETIME_FORMATTER.format(dt);
        }
        return StringUtils.trim(String.valueOf(value));
    }
}
