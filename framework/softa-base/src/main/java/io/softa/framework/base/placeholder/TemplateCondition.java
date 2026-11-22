package io.softa.framework.base.placeholder;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * The comparisons a condition block can make, as the block stores them in {@code data-tpl-op}.
 *
 * <p>A block keeps the field, the comparison and the value apart instead of an expression, so the
 * condition handed to the template engine is always one this class wrote: an author cannot put an
 * expression of their own into a template through it.
 */
public enum TemplateCondition {
    IS_TRUE("isTrue", false),
    IS_FALSE("isFalse", false),
    IS_EMPTY("isEmpty", false),
    IS_NOT_EMPTY("isNotEmpty", false),
    EQ("eq", true),
    NE("ne", true),
    GT("gt", true),
    GE("ge", true),
    LT("lt", true),
    LE("le", true);

    /** How the value of a comparison is written into the template. */
    public enum ValueType {
        STRING("string"), NUMBER("number"), BOOLEAN("boolean");

        private final String code;

        ValueType(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /** The type a block names in {@code data-tpl-value-type}; text when it names none. */
        public static Optional<ValueType> of(String code) {
            if (code == null || code.isBlank()) {
                return Optional.of(STRING);
            }
            return Arrays.stream(values()).filter(type -> type.code.equals(code.trim())).findFirst();
        }
    }

    private final String code;
    private final boolean needsValue;

    TemplateCondition(String code, boolean needsValue) {
        this.code = code;
        this.needsValue = needsValue;
    }

    public String code() {
        return code;
    }

    /** Whether the comparison is made against a value the author typed. */
    public boolean needsValue() {
        return needsValue;
    }

    /** Whether the comparison orders two numbers, and so applies to numbers only. */
    public boolean ordering() {
        return this == GT || this == GE || this == LT || this == LE;
    }

    public static Optional<TemplateCondition> of(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(op -> op.code.equals(code.trim())).findFirst();
    }

    /** The engine function a translated condition calls; see {@link #holds}. */
    static final String FUNCTION = "tplCondition";

    /**
     * The template-engine condition for {@code reference} (the field, already rooted where its
     * value is read from): a call of {@link #FUNCTION}, which decides in {@link #holds}.
     *
     * <p>Decided here in code rather than with the engine's own operators, because a template's
     * variables are not always of the type a comparison expects — a number arrives as text from a
     * preview form or a mapped flow variable — and the engine answers a mismatch with an error that
     * fails the send, where the fair answer is a comparison that reads the text as the number it is.
     *
     * @throws IllegalArgumentException when the value cannot be written as {@code type}
     */
    String expression(String reference, String value, ValueType type) {
        if (!needsValue) {
            return FUNCTION + "(" + reference + ", '" + code + "')";
        }
        ValueType compared = ordering() ? ValueType.NUMBER : type;
        requireOfType(value, compared);
        return FUNCTION + "(" + reference + ", '" + code + "', " + quoted(value) + ", '" + compared.code() + "')";
    }

    /**
     * Whether the condition holds for a value.
     *
     * <ul>
     *   <li>{@code isTrue} / {@code isFalse}: true is the boolean, or the text {@code true}; anything
     *       else — a missing value included — is false, the way a checkbox nobody ticked reads.</li>
     *   <li>{@code isEmpty} / {@code isNotEmpty}: nothing, blank text, or an empty list or map.</li>
     *   <li>{@code eq} / {@code ne}: as numbers, booleans or text, by {@code type}; a missing value
     *       equals the empty text.</li>
     *   <li>{@code gt} / {@code ge} / {@code lt} / {@code le}: as numbers; false when there is no
     *       number to compare, rather than an error.</li>
     * </ul>
     */
    public static boolean holds(Object actual, String op, String expected, String type) {
        TemplateCondition condition = of(op).orElseThrow(() ->
                new IllegalArgumentException("Unknown template condition '" + op + "'."));
        ValueType valueType = ValueType.of(type).orElse(ValueType.STRING);
        return switch (condition) {
            case IS_TRUE -> isTrue(actual);
            case IS_FALSE -> !isTrue(actual);
            case IS_EMPTY -> isEmpty(actual);
            case IS_NOT_EMPTY -> !isEmpty(actual);
            case EQ -> equal(actual, expected, valueType);
            case NE -> !equal(actual, expected, valueType);
            case GT, GE, LT, LE -> {
                BigDecimal left = numberOf(actual);
                BigDecimal right = numberOf(expected);
                if (left == null || right == null) {
                    yield false;
                }
                int order = left.compareTo(right);
                yield switch (condition) {
                    case GT -> order > 0;
                    case GE -> order >= 0;
                    case LT -> order < 0;
                    default -> order <= 0;
                };
            }
        };
    }

    private static boolean isTrue(Object actual) {
        return Boolean.TRUE.equals(actual) || (actual instanceof CharSequence text
                && "true".equalsIgnoreCase(text.toString().trim()));
    }

    private static boolean isEmpty(Object actual) {
        if (actual == null) {
            return true;
        }
        if (actual instanceof CharSequence text) {
            return text.toString().isBlank();
        }
        if (actual instanceof Collection<?> items) {
            return items.isEmpty();
        }
        return actual instanceof Map<?, ?> map && map.isEmpty();
    }

    private static boolean equal(Object actual, String expected, ValueType type) {
        return switch (type) {
            case NUMBER -> {
                BigDecimal left = numberOf(actual);
                BigDecimal right = numberOf(expected);
                yield left != null && right != null && left.compareTo(right) == 0;
            }
            case BOOLEAN -> isTrue(actual) == isTrue(expected);
            case STRING -> (actual == null ? "" : String.valueOf(actual)).equals(expected == null ? "" : expected);
        };
    }

    private static BigDecimal numberOf(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number || value instanceof CharSequence) {
            try {
                return new BigDecimal(value.toString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static void requireOfType(String value, ValueType type) {
        String text = value == null ? "" : value.trim();
        if (type == ValueType.NUMBER && numberOf(text) == null) {
            throw new IllegalArgumentException("'" + text + "' is not a number.");
        }
        if (type == ValueType.BOOLEAN && !"true".equals(text) && !"false".equals(text)) {
            throw new IllegalArgumentException("'" + text + "' is neither true nor false.");
        }
    }

    /**
     * The value as a string literal the template engine reads back unchanged: nothing in it is
     * taken as template syntax. Single quotes — a double-quoted string interpolates {@code #{...}}.
     */
    static String quoted(String value) {
        return "'" + (value == null ? "" : value).replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
