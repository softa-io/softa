package io.softa.framework.orm.meta;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.FilterEvaluator;
import io.softa.framework.orm.domain.FilterEvaluator.RefKind;
import io.softa.framework.orm.domain.FilterEvaluator.ValueRef;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.dto.DTOFieldObject;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.FilterType;

/**
 * Everything a field declares about which values it accepts and when it applies — stored as one
 * {@code sys_field.constraints} column ({@code FieldType.DTO}, JSON) and served unchanged to the
 * frontend, which evaluates the same object against the form.
 *
 * <p>Three kinds of key, told apart by one criterion: <b>does the rule look at other fields, and what
 * does it conclude?</b>
 * <ul>
 *   <li><b>Value domain</b> ({@code min} / {@code max} / {@code pattern}): looks at this value only,
 *       concludes reject. Enforced by the numeric and string processors after coercion / trim
 *       ({@link ValueConstraints}).</li>
 *   <li><b>Field state</b> ({@code requiredWhen} / {@code hiddenWhen} / {@code readonlyWhen}): looks at
 *       other fields, concludes required / hidden / readonly. {@code requiredWhen} may also be the JSON
 *       literal {@code true} — application-level required on a nullable column.</li>
 *   <li><b>Validity</b> ({@code invalidWhen}): looks at other fields, concludes reject, with
 *       {@code message}.</li>
 * </ul>
 * The state and validity conditions are evaluated on the row as it will be after the write (the patch
 * merged onto the stored row) by {@link FilterEvaluator}; the pipeline registers the fields a condition
 * reads so the update path fetches them ({@code DataUpdatePipeline}).
 *
 * <p>Nothing here renders DDL. Tightening a rule is a redeploy, not a migration, and rows written
 * before it stay valid — the behaviour a business rule wants and a {@code CHECK} would not give.
 * A field with no declaration has a {@code null} column, never {@code "{}"} ({@link #isEmpty()}).
 *
 * @param min smallest accepted value as a decimal literal; numeric types only
 * @param max largest accepted value as a decimal literal; numeric types only
 * @param pattern regex the whole value must match; STRING / TEXT only
 * @param message shown when a bound, the pattern or {@code invalidWhen} rejects; its own i18n key
 * @param requiredWhen when the field is required; {@code true} for always (application level)
 * @param hiddenWhen when the field is hidden — and its required / invalid rules not evaluated
 * @param readonlyWhen when assigning the field is rejected
 * @param invalidWhen when the row's values make this field invalid
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(using = FieldConstraints.Deserializer.class)
public record FieldConstraints(
        @Nullable String min,
        @Nullable String max,
        @Nullable String pattern,
        @Nullable String message,
        @Nullable FieldCondition requiredWhen,
        @Nullable Filters hiddenWhen,
        @Nullable Filters readonlyWhen,
        @Nullable Filters invalidWhen) implements DTOFieldObject, Serializable {

    /** Where a regex can mean something: the two field types that hold free text. */
    public static final Set<FieldType> PATTERN_TYPES = Set.of(FieldType.STRING, FieldType.TEXT);

    /**
     * Build from the annotation attributes; null when nothing is declared, so an undeclared field
     * stores {@code NULL} rather than an empty object.
     *
     * @throws IllegalStateException when a condition does not parse as a filter expression, or the
     *         literal {@code "true"} is used where only {@code requiredWhen} accepts it
     */
    public static @Nullable FieldConstraints of(String min, String max, String pattern, String message,
                                                String requiredWhen, String hiddenWhen,
                                                String readonlyWhen, String invalidWhen, String where) {
        FieldConstraints constraints = new FieldConstraints(
                blankToNull(min), blankToNull(max), blankToNull(pattern), blankToNull(message),
                parseCondition("requiredWhen", requiredWhen, where),
                parseFilters("hiddenWhen", hiddenWhen, where),
                parseFilters("readonlyWhen", readonlyWhen, where),
                parseFilters("invalidWhen", invalidWhen, where));
        return constraints.isEmpty() ? null : constraints;
    }

    private static @Nullable FieldCondition parseCondition(String attribute, String text, String where) {
        try {
            return FieldCondition.parse(text);
        } catch (RuntimeException e) {
            throw new IllegalStateException("@Field(" + attribute + ") on " + where
                    + " is not a filter expression: " + text + " — " + e.getMessage(), e);
        }
    }

    private static @Nullable Filters parseFilters(String attribute, String text, String where) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        if (FieldCondition.ALWAYS_LITERAL.equalsIgnoreCase(text.trim())) {
            throw new IllegalStateException("@Field(" + attribute + ") on " + where
                    + " does not accept \"true\": an unconditional " + attribute.replace("When", "")
                    + " is the plain flag, only requiredWhen has an always-form.");
        }
        Filters filters;
        try {
            filters = Filters.of(text);
        } catch (RuntimeException e) {
            throw new IllegalStateException("@Field(" + attribute + ") on " + where
                    + " is not a filter expression: " + text + " — " + e.getMessage(), e);
        }
        if (Filters.isEmpty(filters)) {
            throw new IllegalStateException("@Field(" + attribute + ") on " + where + " is an empty condition.");
        }
        return filters;
    }

    private static @Nullable String blankToNull(String s) {
        return StringUtils.isBlank(s) ? null : s;
    }

    /** True when every key is null — the declaration that stores as {@code NULL}. */
    @JsonIgnore   // getter-shaped, but not a key: the JSON is the eight attributes and nothing else
    public boolean isEmpty() {
        return min == null && max == null && pattern == null && message == null
                && requiredWhen == null && hiddenWhen == null && readonlyWhen == null && invalidWhen == null;
    }

    /** Whether any of the four conditions is declared — the part that reads other fields. */
    public boolean hasConditions() {
        return requiredWhen != null || hiddenWhen != null || readonlyWhen != null || invalidWhen != null;
    }

    /**
     * The fields of the same row the conditions read: every field in the field slot (minus the
     * reserved {@code @mode} / {@code @userId}) and every {@code {{ @field }}} reference in the value
     * slot. This is what the update pipeline fetches from the stored row so a condition sees the full
     * picture when the patch carries only part of it.
     */
    public Set<String> referencedFields() {
        Set<String> fields = new LinkedHashSet<>();
        for (Filters condition : conditions()) {
            collectReferences(condition, fields);
        }
        return fields;
    }

    private List<Filters> conditions() {
        List<Filters> out = new ArrayList<>(4);
        if (requiredWhen != null && requiredWhen.getFilters() != null) {
            out.add(requiredWhen.getFilters());
        }
        if (hiddenWhen != null) out.add(hiddenWhen);
        if (readonlyWhen != null) out.add(readonlyWhen);
        if (invalidWhen != null) out.add(invalidWhen);
        return out;
    }

    private static void collectReferences(Filters filters, Set<String> into) {
        if (FilterType.TREE.equals(filters.getType())) {
            filters.getChildren().forEach(child -> collectReferences(child, into));
        } else if (FilterType.LEAF.equals(filters.getType())) {
            FilterUnit unit = filters.getFilterUnit();
            unit.getEffectiveFields().stream()
                    .filter(f -> !FilterEvaluator.RESERVED_VARIABLES.contains(f))
                    .forEach(into::add);
            collectValueReferences(unit.getValue(), into);
        }
    }

    private static void collectValueReferences(@Nullable Object value, Set<String> into) {
        if (value instanceof Collection<?> values) {
            values.forEach(v -> collectValueReferences(v, into));
            return;
        }
        ValueRef ref = FilterEvaluator.parseValueRef(value);
        if (ref.kind() == RefKind.FIELD) {
            into.add(ref.name());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Boot-time validation
    // ---------------------------------------------------------------------------------------------

    /**
     * Check the declaration against the field it sits on and the model it belongs to. Every problem is
     * a mistake in something written by hand, so it throws — the annotation lane turns that into a boot
     * failure in front of whoever wrote it; the catalog load logs it and drops the declaration, because
     * one bad row must not stop a model from being written.
     *
     * @param fieldType the resolved type of the field carrying the declaration
     * @param dynamic whether that field is dynamic (not stored — nothing to require or reject)
     * @param where {@code Model.field}, for messages
     * @param fieldTypeOf the type of a sibling field by name, or null when the model has no such field
     * @return warnings worth logging that are not errors (a pattern without a message, a regex construct
     *         JavaScript does not share)
     * @throws IllegalStateException on the first error
     */
    public List<String> validate(FieldType fieldType, boolean dynamic, String where,
                                 Function<String, @Nullable FieldType> fieldTypeOf) {
        List<String> warnings = new ArrayList<>();
        validateValueDomain(fieldType, where, warnings);
        if (hasConditions() && dynamic) {
            throw new IllegalStateException("@Field conditions on " + where
                    + " are declared on a dynamic field: a dynamic field is not stored and cannot be"
                    + " required, hidden, readonly or invalid. Dynamic fields can be referenced by a condition.");
        }
        if (requiredWhen != null && !requiredWhen.isAlways()) {
            validateCondition("requiredWhen", requiredWhen.getFilters(), where, fieldTypeOf);
        }
        if (hiddenWhen != null) validateCondition("hiddenWhen", hiddenWhen, where, fieldTypeOf);
        if (readonlyWhen != null) validateCondition("readonlyWhen", readonlyWhen, where, fieldTypeOf);
        if (invalidWhen != null) {
            validateCondition("invalidWhen", invalidWhen, where, fieldTypeOf);
            if (message == null) {
                warnings.add("@Field(invalidWhen) on " + where + " has no constraintMessage; the user will"
                        + " see a generated sentence that cannot explain the rule.");
            }
        }
        return warnings;
    }

    private void validateValueDomain(FieldType fieldType, String where, List<String> warnings) {
        if ((min != null || max != null) && !FieldType.NUMERIC_TYPES.contains(fieldType)) {
            throw new IllegalStateException("@Field(min / max) on " + where + " applies to numeric"
                    + " field types only, but the resolved field type is " + fieldType
                    + ". For a string use pattern, for a length use length.");
        }
        if (pattern != null && !PATTERN_TYPES.contains(fieldType)) {
            throw new IllegalStateException("@Field(pattern) on " + where + " applies to STRING and"
                    + " TEXT only, but the resolved field type is " + fieldType + ".");
        }
        BigDecimal lower = parseBound(min, "min", where);
        BigDecimal upper = parseBound(max, "max", where);
        if (lower != null && upper != null && lower.compareTo(upper) > 0) {
            throw new IllegalStateException("@Field(min / max) on " + where + " declares min " + min
                    + " above max " + max + ", which no value can satisfy.");
        }
        if (pattern != null) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException("@Field(pattern) on " + where
                        + " is not a valid regular expression: " + e.getDescription(), e);
            }
            if (pattern.contains("(?<=") || pattern.contains("(?<!")) {
                warnings.add("@Field(pattern) on " + where + " uses lookbehind, which not every JavaScript"
                        + " engine supports; the frontend applies the same pattern.");
            }
            if (message == null) {
                warnings.add("@Field(pattern) on " + where + " has no constraintMessage; showing the"
                        + " regex to the user says nothing they can act on.");
            }
        }
    }

    private static @Nullable BigDecimal parseBound(@Nullable String bound, String attribute, String where) {
        if (bound == null) {
            return null;
        }
        try {
            return new BigDecimal(bound);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("@Field(" + attribute + ") on " + where
                    + " is not a decimal literal: `" + bound + "`.", e);
        }
    }

    private static void validateCondition(String attribute, Filters filters, String where,
                                          Function<String, @Nullable FieldType> fieldTypeOf) {
        if (FilterType.TREE.equals(filters.getType())) {
            filters.getChildren().forEach(child -> validateCondition(attribute, child, where, fieldTypeOf));
            return;
        }
        if (!FilterType.LEAF.equals(filters.getType())) {
            return;
        }
        FilterUnit unit = filters.getFilterUnit();
        String prefix = "@Field(" + attribute + ") on " + where + ": ";
        Operator op = unit.getOperator();
        if (Operator.PARENT_OF.equals(op) || Operator.CHILD_OF.equals(op)) {
            throw new IllegalStateException(prefix + op.getName()
                    + " needs a query and cannot be evaluated against the row being written: " + unit);
        }
        FieldType leftType = null;
        for (String field : unit.getEffectiveFields()) {
            if (field.startsWith("@")) {
                if (!FilterEvaluator.RESERVED_VARIABLES.contains(field)) {
                    throw new IllegalStateException(prefix + "unknown reserved variable `" + field
                            + "`; only " + FilterEvaluator.RESERVED_VARIABLES + " are available.");
                }
                continue;
            }
            FieldType type = fieldTypeOf.apply(field);
            if (type == null) {
                throw new IllegalStateException(prefix + "references field `" + field
                        + "`, which does not exist on the model.");
            }
            leftType = type;
        }
        if (!unit.isTuple()) {
            validateValue(unit.getValue(), leftType, unit.getField(), prefix, fieldTypeOf);
        }
    }

    private static void validateValue(@Nullable Object value, @Nullable FieldType leftType, String leftField,
                                      String prefix, Function<String, @Nullable FieldType> fieldTypeOf) {
        if (value instanceof Collection<?> values) {
            values.forEach(v -> validateValue(v, leftType, leftField, prefix, fieldTypeOf));
            return;
        }
        if (value instanceof String text && text.trim().startsWith("{{") && text.trim().endsWith("}}")) {
            ValueRef ref;
            try {
                ref = FilterEvaluator.parseValueRef(text);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(prefix + e.getMessage(), e);
            }
            if (!ref.isReference()) {
                throw new IllegalStateException(prefix + "unknown placeholder " + text.trim()
                        + "; a value may reference a field ({{ @field }}) or one of "
                        + FilterEvaluator.ENV_TOKENS + ", optionally with an ISO-8601 offset.");
            }
            if (ref.kind() == RefKind.FIELD) {
                FieldType refType = fieldTypeOf.apply(ref.name());
                if (refType == null) {
                    throw new IllegalStateException(prefix + "references field `" + ref.name()
                            + "`, which does not exist on the model.");
                }
                if (leftType != null && !comparable(leftType, refType)) {
                    throw new IllegalStateException(prefix + "compares " + leftType + " " + leftField
                            + " to " + refType + " " + ref.name() + ", which are not comparable.");
                }
                if (ref.offset() != null) {
                    validateOffset(ref, refType == FieldType.DATE, refType == FieldType.DATE_TIME, prefix, ref.name());
                }
            } else if (ref.offset() != null) {
                boolean day = FilterEvaluator.DATE_TOKENS.contains(ref.name());
                boolean instant = io.softa.framework.base.constant.EnvConstant.NOW.equals(ref.name());
                validateOffset(ref, day, instant, prefix, ref.name());
            }
        }
    }

    private static void validateOffset(ValueRef ref, boolean isDay, boolean isInstant, String prefix, String base) {
        if (!isDay && !isInstant) {
            throw new IllegalStateException(prefix + "an offset needs a date base, but `" + base
                    + "` is not a DATE / DATE_TIME field or TODAY / NOW.");
        }
        if (isDay && !(ref.offset() instanceof Period)) {
            throw new IllegalStateException(prefix + "`" + base + "` is a calendar day; its offset must be"
                    + " a period (P13Y, P6M, P1D), not a time duration.");
        }
    }

    /**
     * Whether two field types can be compared in a condition: the same kind of thing on both sides.
     * A relation id compares with a number (surrogate id) or text (code-as-id) — the row carries the
     * FK value, and {@code ["reportsTo", "=", "{{ @id }}"]} is the intended use.
     */
    static boolean comparable(FieldType a, FieldType b) {
        String ka = kind(a);
        String kb = kind(b);
        if (ka.equals(kb)) {
            return true;
        }
        Set<String> idLike = Set.of("id", "number", "text");
        return ("id".equals(ka) || "id".equals(kb)) && idLike.contains(ka) && idLike.contains(kb);
    }

    private static String kind(FieldType type) {
        if (FieldType.NUMERIC_TYPES.contains(type)) return "number";
        return switch (type) {
            case DATE -> "date";
            case DATE_TIME -> "datetime";
            case TIME -> "time";
            case BOOLEAN -> "boolean";
            case FILE, ONE_TO_ONE, MANY_TO_ONE -> "id";
            default -> "text";
        };
    }

    /**
     * Reads the stored JSON leniently: a row that does not parse — a hand-written {@code sys_field}
     * row, a studio payload from an older shape — is logged and read as <b>no constraints</b>, so one
     * bad declaration cannot stop the catalog from loading and every write to the model from working.
     * The semantic checks ({@link #validate}) run afterwards in {@code ModelManager} and drop a
     * declaration the same way.
     */
    @Slf4j
    public static final class Deserializer extends ValueDeserializer<FieldConstraints> {
        @Override
        public @Nullable FieldConstraints deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            JsonNode node = p.readValueAsTree();
            if (node == null || node.isNull()) {
                return null;
            }
            // Every member is read back as the text the annotation would have carried — a condition
            // stored as a JSON array is its own text — and parsed by the one factory the annotation
            // lane uses, so the two lanes cannot drift in what they accept. A member that does not
            // parse is dropped on its own: a hand-written or older-studio row costs the field that one
            // rule, never the rest of its declaration, and never the catalog load.
            FieldConstraints c = of(
                    text(node, "min"), text(node, "max"), text(node, "pattern"), text(node, "message"),
                    member(node, "requiredWhen"), member(node, "hiddenWhen"),
                    member(node, "readonlyWhen"), member(node, "invalidWhen"), "stored constraints");
            return c == null || c.isEmpty() ? null : c;
        }

        /** One condition member as text, or null when it is absent or does not parse. */
        private static @Nullable String member(JsonNode node, String key) {
            String declared = text(node, key);
            if (declared == null) {
                return null;
            }
            try {
                // parsed here only to find out whether it parses; `of` does the real work
                if ("requiredWhen".equals(key)) {
                    FieldCondition.parse(declared);
                } else if (!FieldCondition.ALWAYS_LITERAL.equalsIgnoreCase(declared.trim())
                        && Filters.isEmpty(Filters.of(declared))) {
                    throw new IllegalStateException("empty condition");
                } else if (FieldCondition.ALWAYS_LITERAL.equalsIgnoreCase(declared.trim())) {
                    throw new IllegalStateException("only requiredWhen has an always-form");
                }
                return declared;
            } catch (RuntimeException e) {
                log.error("Stored field constraint {} = {} does not parse and that rule is ignored: {}",
                        key, declared, e.getMessage());
                return null;
            }
        }

        /**
         * A member as the text the annotation would have carried: a scalar's value, a structured
         * value's JSON, and every spelling of "nothing declared here" as null — absent, null, an
         * empty array or object, and {@code false} (the sibling {@code FieldCondition} lane reads
         * {@code false} as no condition). {@code true} keeps its literal, so only {@code requiredWhen}
         * accepts it and the other three still reject it.
         */
        private static @Nullable String text(JsonNode node, String key) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                return null;
            }
            if (value.isBoolean()) {
                return value.asBoolean() ? FieldCondition.ALWAYS_LITERAL : null;
            }
            if (value.isObject() || value.isArray()) {
                return value.isEmpty() ? null : value.toString();
            }
            return blankToNull(value.asString());
        }
    }
}
