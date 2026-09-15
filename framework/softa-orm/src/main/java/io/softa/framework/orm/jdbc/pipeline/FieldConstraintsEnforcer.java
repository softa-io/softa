package io.softa.framework.orm.jdbc.pipeline;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.EvalContext;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.FilterEvaluator;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.FieldCondition;
import io.softa.framework.orm.meta.FieldConstraints;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.validation.WriteValidationException;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.utils.ReflectTool;

/**
 * Applies the conditional field constraints — {@code requiredWhen} / {@code hiddenWhen} /
 * {@code readonlyWhen} / {@code invalidWhen} — to the rows of one write.
 *
 * <p>Runs <b>before</b> the field-processor chain, on the raw values: on create the request row with
 * the declared {@code defaultValue}s filled in (the chain fills them later, and a condition reading
 * {@code active} must see the default {@code true} a form never sent), on update the patch merged
 * onto the stored row. The chain converts field by field, so a neighbour a
 * condition reads might or might not have been coerced yet depending on declaration order; reading
 * the values as they arrived gives create and update — and the frontend, which reads the form — the
 * same picture. The value domain ({@code min} / {@code max} / {@code pattern}) is the opposite case
 * and stays in the processors: it needs the coerced value.
 *
 * <p>Rules, written down because the frontend evaluator must give the same answers:
 * <ol>
 *   <li><b>Hidden fields are not checked.</b> {@code hidden} or a matching {@code hiddenWhen} skips the
 *       field's required / readonly / invalid rules — what the form does not show it cannot demand,
 *       otherwise a record with a hidden required field could never be saved.</li>
 *   <li><b>On update, a field is evaluated only when the patch touches it</b> — the field itself, or a
 *       field one of its conditions reads. A row whose {@code reasonDescription} is legitimately empty
 *       is not rejected by an unrelated edit; changing {@code reason} to {@code Others} is.
 *       {@code requiredWhen = true} reads nothing, so it fires on create and when the field is sent
 *       (clearing it is rejected; not sending it is not) — the "cannot clear, may omit" contract.</li>
 *   <li><b>{@code readonlyWhen} rejects an assignment</b>, not a value: the patch names the field and the
 *       value differs from what is stored (on create: is not null).</li>
 *   <li><b>{@code invalidWhen} rejects with the declared message</b>; without one, a generated sentence
 *       that names the field.</li>
 *   <li><b>A condition may read a {@code dynamic} cascaded field</b> ({@code bankId.code} declared as
 *       {@code bankCode}). It has no column, so its value is fetched from the related row by the FK
 *       the row carries — once per FK value per write — and laid over a working copy of the row
 *       before the conditions run. A stored cascaded field is a column and needs nothing.</li>
 * </ol>
 * The static {@code required} check stays in the processors — a {@code NOT NULL} column cannot be
 * skipped by hiding the field, the database would reject the row anyway.
 */
public final class FieldConstraintsEnforcer {

    /** Reads one attribute of a related row by id — the lookup a {@code dynamic} cascaded reference needs. */
    @FunctionalInterface
    public interface RelatedRowReader {
        /**
         * @param relatedModel the model the FK points at
         * @param path the attribute to read on it (may be a further cascade, {@code a.b})
         * @param id the FK value
         * @return the related row holding {@code path}, or null when it does not exist
         */
        @Nullable Map<String, Object> read(String relatedModel, String path, Serializable id);
    }

    /**
     * The types whose value is a set of members stored as comma-joined text. A to-many relation is
     * deliberately absent: it has no column of its own, so there is no stored side to compare with.
     */
    private static final Set<FieldType> MULTI_VALUE_TYPES = Set.of(
            FieldType.MULTI_OPTION, FieldType.MULTI_STRING, FieldType.MULTI_FILE);

    private final String modelName;
    private final AccessType accessType;
    private final List<MetaField> conditionalFields;
    private final Function<String, @Nullable MetaField> fieldOf;
    private final Function<String, @Nullable FieldType> typeOf;
    private final EvalContext ctx;
    private final RelatedRowReader relatedRows;
    /** Per conditional field, the names its conditions read — computed once, not per row. */
    private final Map<String, Set<String>> referencedByField = new HashMap<>();
    /** Per conditional field, the stored columns those references live in. */
    private final Map<String, Set<String>> storedByField = new HashMap<>();
    /** On create: the declared defaults of every field a condition may read, filled into the view. */
    private final Map<String, Object> createDefaults = new HashMap<>();
    /**
     * Related rows already fetched during this write: {@code model/id#path → row}. The path belongs in
     * the key because the read selects only that one attribute — two cascaded references to the same
     * related row ask for different columns, and the second would read a column the first never
     * fetched. A miss is remembered as an absent row, so a dangling FK is looked up once, not per row.
     */
    private final Map<String, Optional<Map<String, Object>>> relatedCache = new HashMap<>();

    /**
     * @param modelName the model being written
     * @param accessType CREATE or UPDATE
     * @param conditionalFields the fields carrying conditions ({@link MetaModel#getConditionalFields()})
     * @param fieldOf a field of the model by name, or null when unknown; drives value coercion and
     *                the dynamic-cascade lookup
     * @param ctx reserved variables and environment tokens for this write
     * @param relatedRows how a dynamic cascaded reference fetches its related row
     */
    public FieldConstraintsEnforcer(String modelName, AccessType accessType, List<MetaField> conditionalFields,
                                    Function<String, @Nullable MetaField> fieldOf, EvalContext ctx,
                                    RelatedRowReader relatedRows) {
        this.modelName = modelName;
        this.accessType = accessType;
        this.conditionalFields = conditionalFields;
        this.fieldOf = fieldOf;
        this.typeOf = field -> {
            MetaField metaField = fieldOf.apply(field);
            return metaField == null ? null : metaField.getFieldType();
        };
        this.ctx = ctx;
        this.relatedRows = relatedRows;
        for (MetaField field : conditionalFields) {
            Set<String> refs = field.getConstraints().referencedFields();
            referencedByField.put(field.getFieldName(), refs);
            storedByField.put(field.getFieldName(), storedReferences(field, fieldOf));
            if (AccessType.CREATE.equals(accessType)) {
                rememberDefault(field);
                refs.forEach(ref -> rememberDefault(fieldOf.apply(ref)));
            }
        }
    }

    private void rememberDefault(@Nullable MetaField field) {
        if (field != null && field.getDefaultValueObject() != null) {
            createDefaults.put(field.getFieldName(), field.getDefaultValueObject());
        }
    }

    /**
     * The enforcer for a live write, reading the model from {@link ModelManager}; null when the model
     * declares no conditions, so the pipelines pay nothing for the common case.
     */
    public static @Nullable FieldConstraintsEnforcer forModel(String modelName, AccessType accessType) {
        List<MetaField> conditional = ModelManager.getModel(modelName).getConditionalFields();
        if (conditional.isEmpty()) {
            return null;
        }
        return new FieldConstraintsEnforcer(modelName, accessType, conditional,
                field -> ModelManager.getModelFieldOrNull(modelName, field), EvalContext.of(accessType),
                FieldConstraintsEnforcer::readRelatedRow);
    }

    /**
     * The live lookup: the related row by id, with {@code path} in its field list, permission checks
     * bypassed — the same read {@code XToOneGroupProcessor} does for a cascaded field.
     */
    static @Nullable Map<String, Object> readRelatedRow(String relatedModel, String path, Serializable id) {
        Filters filters = Filters.of(ModelConstant.ID, Operator.EQUAL, id);
        MetaModel related = ModelManager.getModel(relatedModel);
        if (related.isActiveControl()) {
            filters.in(ModelConstant.ACTIVE_CONTROL_FIELD, List.of(true, false));
        }
        if (related.isSoftDelete()) {
            filters.in(ModelConstant.SOFT_DELETED_FIELD, List.of(true, false));
        }
        FlexQuery query = new FlexQuery(Set.of(ModelConstant.ID, path), filters);
        query.setFilterControl(FilterControl.bypassAll());
        List<Map<String, Object>> rows = ReflectTool.searchList(relatedModel, query);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * The stored columns an update must fetch so the conditions can be evaluated: for every
     * conditional field the patch touches (itself or a field it reads), the field and everything it
     * reads. Walks {@link FieldConstraints#referencedFields()} — the same registration the computed
     * fields do for their dependencies.
     *
     * @param model the model
     * @param patchFields the fields the update carries
     * @param isStored whether a field of the model is a stored column
     */
    public static Set<String> columnsToRead(MetaModel model, Set<String> patchFields, Function<String, @Nullable MetaField> fieldOf) {
        Set<String> columns = new HashSet<>();
        for (MetaField field : model.getConditionalFields()) {
            Set<String> refs = storedReferences(field, fieldOf);
            boolean touched = patchFields.contains(field.getFieldName())
                    || refs.stream().anyMatch(patchFields::contains);
            if (!touched) {
                continue;
            }
            if (!field.isDynamic()) {
                columns.add(field.getFieldName());
            }
            columns.addAll(refs);
        }
        return columns;
    }

    /**
     * The stored columns a field's conditions depend on: every referenced stored field, and for a
     * {@code dynamic} cascaded reference the FK it hangs on (the attribute itself has no column).
     */
    private static Set<String> storedReferences(MetaField field, Function<String, @Nullable MetaField> fieldOf) {
        Set<String> stored = new HashSet<>();
        for (String ref : field.getConstraints().referencedFields()) {
            MetaField referenced = fieldOf.apply(ref);
            if (referenced == null) {
                continue;
            }
            if (referenced.isDynamicCascadedField()) {
                stored.add(referenced.getDependentFields().getFirst());
            } else if (!referenced.isDynamic()) {
                stored.add(ref);
            }
        }
        return stored;
    }

    /**
     * Create: every conditional field is evaluated against the request row, seen with the declared
     * defaults of the fields it reads — the same fill the chain applies afterwards
     * ({@code computeIfAbsent}: an explicit null takes the default too).
     */
    public void enforceCreate(Map<String, Object> row) {
        Map<String, Object> view = row;
        for (Map.Entry<String, Object> dflt : createDefaults.entrySet()) {
            if (row.get(dflt.getKey()) == null) {
                if (view == row) {
                    view = new HashMap<>(row);
                }
                view.put(dflt.getKey(), dflt.getValue());
            }
        }
        for (MetaField field : conditionalFields) {
            enforce(field, withDynamicReferences(field, view), row, null);
        }
    }

    /**
     * Update: a conditional field is evaluated when the patch touches it or a field it reads.
     *
     * @param mergedRow the patch merged onto the stored row — what the row will be after the write
     * @param patch the fields the request actually sent
     * @param originalRow the stored row (the columns that were fetched), for the readonly comparison
     */
    public void enforceUpdate(Map<String, Object> mergedRow, Map<String, Object> patch, @Nullable Map<String, Object> originalRow) {
        for (MetaField field : conditionalFields) {
            Set<String> refs = storedByField.get(field.getFieldName());
            boolean touched = patch.containsKey(field.getFieldName())
                    || refs.stream().anyMatch(patch::containsKey);
            if (touched) {
                enforce(field, withDynamicReferences(field, mergedRow), patch, originalRow);
            }
        }
    }

    /**
     * The row as the conditions see it: the row itself, plus the value of every {@code dynamic}
     * cascaded field a condition references, fetched from the related row by the FK. A working copy —
     * the value never enters the row that is written.
     */
    private Map<String, Object> withDynamicReferences(MetaField field, Map<String, Object> row) {
        Map<String, Object> view = null;
        for (String ref : referencedByField.get(field.getFieldName())) {
            MetaField referenced = fieldOf.apply(ref);
            if (referenced == null || !referenced.isDynamicCascadedField() || row.containsKey(ref)) {
                continue;
            }
            List<String> chain = referenced.getDependentFields();
            MetaField fk = fieldOf.apply(chain.getFirst());
            Object fkValue = row.get(chain.getFirst());
            if (fk == null || StringUtils.isBlank(fk.getRelatedModel()) || !IdUtils.validId(fkValue)) {
                continue;
            }
            String path = chain.get(1);
            String key = fk.getRelatedModel() + "/" + fkValue + "#" + path;
            Map<String, Object> relatedRow = relatedCache.computeIfAbsent(key,
                    k -> Optional.ofNullable(relatedRows.read(fk.getRelatedModel(), path, (Serializable) fkValue)))
                    .orElse(null);
            if (view == null) {
                view = new HashMap<>(row);
            }
            view.put(ref, relatedRow == null ? null : valueAt(relatedRow, path));
        }
        return view == null ? row : view;
    }

    /** {@code a.b} on a nested map, or the flat key when the read already flattened it. */
    private static @Nullable Object valueAt(Map<String, Object> row, String path) {
        if (row.containsKey(path)) {
            return row.get(path);
        }
        Object current = row;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    private void enforce(MetaField field, Map<String, Object> row, Map<String, Object> patch,
                         @Nullable Map<String, Object> originalRow) {
        FieldConstraints c = field.getConstraints();
        String name = field.getFieldName();
        if (field.isHidden() || matches(c.hiddenWhen(), row)) {
            return;
        }
        Object value = row.get(name);
        // The readonly check looks at what the request assigned, not at the evaluation view (which on
        // create may hold the field's own default).
        if (c.readonlyWhen() != null && patch.containsKey(name) && matches(c.readonlyWhen(), row)
                && assigned(field, patch.get(name), originalRow == null ? null : originalRow.get(name))) {
            throw WriteValidationException.forField(name,
                    "Model field {0}:{1} is readonly in its current state and cannot be assigned!", modelName, name);
        }
        if (requiredNow(c.requiredWhen(), row) && FilterEvaluator.isBlank(value)) {
            throw WriteValidationException.forField(name,
                    "Model field {0}:{1} is required and cannot be empty!", modelName, name);
        }
        if (c.invalidWhen() != null && matches(c.invalidWhen(), row)) {
            // The declared sentence is shown as written — it is not a MessageFormat pattern.
            throw c.message() != null
                    ? WriteValidationException.forField(name, c.message())
                    : WriteValidationException.forField(name,
                            "Model field {0}:{1} is not valid: the value {2} does not satisfy the field''s rule.",
                            modelName, name, String.valueOf(value));
        }
    }

    private boolean requiredNow(@Nullable FieldCondition condition, Map<String, Object> row) {
        if (condition == null) {
            return false;
        }
        return condition.isAlways() || matches(condition.getFilters(), row);
    }

    private boolean matches(@Nullable Filters condition, Map<String, Object> row) {
        return condition != null && FilterEvaluator.matches(condition, row, ctx, typeOf);
    }

    /**
     * Whether the patch changes the field: a new non-null value on create, on update a value that
     * differs from the stored one under the field's type — a stored date arrives as text and the
     * patch as {@code LocalDate}, {@code 10} and {@code 10.00} are the same amount.
     */
    private boolean assigned(MetaField field, @Nullable Object value, @Nullable Object original) {
        if (AccessType.CREATE.equals(accessType)) {
            return value != null;
        }
        if (Objects.equals(value, original)) {
            return false;
        }
        if (FieldType.JSON.equals(field.getFieldType()) || FieldType.DTO.equals(field.getFieldType())) {
            // A JSON column arrives as an object or a list and is stored as text; compare the two as
            // parsed JSON, where an object is equal by its members and an array by its order — so a
            // column the database normalized (key order, spacing) still reads as the value sent.
            return !Objects.equals(jsonNode(value), jsonNode(original));
        }
        if (MULTI_VALUE_TYPES.contains(field.getFieldType())) {
            // A multi-value field arrives as a list and is stored as comma-joined text, so the two
            // sides never match as objects. It is a set: same members, same value, whatever the
            // order and whichever shape each side happens to be in. Decided by the declared type, not
            // by the Java shape — a JSON column also arrives as a list and is stored as its own text,
            // which splitting on commas would mangle.
            return !members(value).equals(members(original));
        }
        return !FilterEvaluator.equal(value, original, field.getFieldType());
    }

    /** A JSON value as a parsed tree, or its text when it does not parse. */
    private static @Nullable Object jsonNode(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof String text
                    ? JsonUtils.stringToObject(text, JsonNode.class)
                    : JsonUtils.objectToJsonNode(value);
        } catch (RuntimeException e) {
            return String.valueOf(value);   // not JSON after all: compare it as the text it is
        }
    }

    /** The members of a multi-value value, from a collection or from the comma-joined text it stores as. */
    private static Set<String> members(@Nullable Object value) {
        Collection<?> elements = value instanceof Collection<?> c
                ? c
                : (value == null ? List.of() : List.of(StringUtils.split(String.valueOf(value), ',')));
        Set<String> members = new HashSet<>();
        for (Object element : elements) {
            // a null element writes as nothing (StringUtils.join), so it is not a member here either
            String text = element == null ? null : StringUtils.trimToNull(String.valueOf(element));
            if (text != null) {
                members.add(text);
            }
        }
        return members;
    }
}
