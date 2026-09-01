package io.softa.starter.metadata.ddl;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IndexType;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.scanner.diff.SchemaDiff;
import io.softa.starter.metadata.scanner.diff.SchemaDiff.Modification;

/**
 * Classifies a {@link SchemaDiff} into per-model DDL operations and tags each
 * as either auto-executable or warn-only.
 *
 * <p>Auto-executable: CREATE TABLE (new model), ADD COLUMN (new field),
 * MODIFY COLUMN (changed field), CHANGE COLUMN (declared field rename or
 * changed {@code columnName}), ADD INDEX.
 *
 * <p>Warn-only (never auto-executed; logged with a copy-paste SQL hint):
 * DROP TABLE (removed model), DROP COLUMN (removed field), DROP INDEX.
 *
 * <p><b>DDL-relevant projection</b>: a same-key modification only becomes DDL
 * work when an attribute that shapes the physical column / index changed
 * (column name, physical type, length/scale, required, default, description-as-COMMENT;
 * index columns / uniqueness). Row-only attribute changes (label, widget, masking,
 * a unique index's violation {@code message}, …) update {@code sys_*} but must not
 * execute DDL — most visibly, an index rebuild (DROP+ADD) for a changed display
 * message would lock a large table for nothing.
 *
 * <p><b>Stored transitions</b>: a field crossing the stored boundary is re-expressed
 * physically — {@code dynamic}/TO_MANY → stored becomes ADD COLUMN (auto); stored →
 * {@code dynamic}/TO_MANY becomes a DROP COLUMN hint (warn-only, like any drop). A
 * same-key {@code columnName} change on a still-stored field is a physical rename and
 * routes to CHANGE COLUMN — rendering it as MODIFY would target a column that doesn't
 * exist.
 *
 * <p>Output is grouped per {@link SysModel}: each affected model gets one
 * {@link ModelOps} record carrying its field / index change buckets.
 * {@code DdlOrchestrator} renders each change as its own statement.
 */
public final class DdlPolicy {

    private DdlPolicy() {}

    /**
     * A field rename at the physical layer: {@code field} is the from-code
     * {@link SysField}; {@code oldColumnName} is the physical column it currently
     * occupies (from the db row), so the dialect can render
     * {@code CHANGE COLUMN old new ...}. Produced for a declared {@code renamedFrom}
     * and for a same-key {@code columnName} change alike.
     */
    public record FieldRename(SysField field, String oldColumnName) {}

    /** Added / updated / renamed / deleted field buckets for an ALTER. */
    public record FieldOps(
            List<SysField> added,
            List<SysField> updated,
            List<FieldRename> renamed,
            List<SysField> deleted) {
        public static final FieldOps EMPTY = new FieldOps(List.of(), List.of(), List.of(), List.of());
    }

    /** Added / updated / deleted index buckets for an ALTER. */
    public record IndexOps(List<SysModelIndex> added, List<SysModelIndex> updated, List<SysModelIndex> deleted) {
        public static final IndexOps EMPTY = new IndexOps(List.of(), List.of(), List.of());
    }

    /**
     * Classification output — one entry per affected model.
     *
     * @param model         the SysModel (from-code for added/modified; from-db for removed)
     * @param operation     what the orchestrator should do
     * @param fields        field change buckets (ALTER ops); {@link FieldOps#EMPTY} for CREATE/DROP
     * @param indexes       index change buckets (ALTER ops); {@link IndexOps#EMPTY} for CREATE
     * @param createFields  for CREATE TABLE: the full field list (else empty)
     * @param createIndexes for CREATE TABLE: the full index list (else empty)
     */
    public record ModelOps(
            SysModel model,
            Operation operation,
            FieldOps fields,
            IndexOps indexes,
            List<SysField> createFields,
            List<SysModelIndex> createIndexes
    ) {}

    public enum Operation {
        /** Auto: CREATE TABLE for new model (incl. inline indexes). */
        CREATE_TABLE,
        /** Auto: ALTER TABLE (mix of ADD/MODIFY/CHANGE COLUMN) + ALTER INDEX. */
        ALTER_TABLE,
        /** Auto for ADD/MODIFY, warn-only for DROP — the mix on one model. */
        ALTER_TABLE_WITH_DROP_WARNING,
        /** Warn-only: model removed, would DROP TABLE. */
        DROP_TABLE_WARNING
    }

    /**
     * Classify the diff into per-model ops.
     *
     * @param diff          the computed schema diff
     * @param allCodeModels lookup of all from-code models by {@code modelName},
     *                      used as fallback when a model has field/index changes
     *                      but no model-level attribute change (so custom
     *                      {@code tableName} / {@code idStrategy} aren't lost).
     */
    public static List<ModelOps> classify(SchemaDiff diff, Map<String, SysModel> allCodeModels) {
        Map<String, List<SysField>> fieldsAddedByModel = groupByModel(diff.fields().added());
        Map<String, List<SysField>> fieldsRemovedByModel = groupByModel(diff.fields().removed());
        Map<String, List<SysField>> fieldsModifiedByModel = new HashMap<>();
        Map<String, List<FieldRename>> fieldsRenamedByModel = new HashMap<>();

        // Route each same-key modification by its physical effect; drop the ones
        // whose delta is row-only.
        for (Modification<SysField> mod : diff.fields().modified()) {
            SysField code = mod.fromCode();
            SysField db = mod.fromDb();
            String modelName = code.getModelName();
            if (mod.kind() == SchemaDiff.Kind.RENAME) {
                // Declared renamedFrom: CHANGE COLUMN old new — mis-routing a rename to
                // MODIFY would target a column that no longer exists.
                bucket(fieldsRenamedByModel, modelName)
                        .add(new FieldRename(code, effectiveColumnName(db)));
                continue;
            }
            boolean storedBefore = SysDdlContextBuilder.isStored(db);
            boolean storedAfter = SysDdlContextBuilder.isStored(code);
            if (storedBefore && !storedAfter) {
                bucket(fieldsRemovedByModel, modelName).add(db);       // physical column orphaned → DROP hint
            } else if (!storedBefore && storedAfter) {
                bucket(fieldsAddedByModel, modelName).add(code);       // column materializes → ADD COLUMN
            } else if (storedBefore) {
                if (!effectiveColumnName(code).equals(effectiveColumnName(db))) {
                    bucket(fieldsRenamedByModel, modelName)
                            .add(new FieldRename(code, effectiveColumnName(db)));
                } else if (ddlRelevantFieldChange(code, db)) {
                    bucket(fieldsModifiedByModel, modelName).add(code);
                }
                // else: row-only attribute delta — no DDL
            }
            // !storedBefore && !storedAfter: nothing physical on either side
        }

        Map<String, List<SysModelIndex>> idxAddedByModel = groupIndexesByModel(diff.modelIndexes().added());
        Map<String, List<SysModelIndex>> idxRemovedByModel = groupIndexesByModel(diff.modelIndexes().removed());
        // An index modification only rebuilds (DROP+ADD) when the physical definition
        // changed; message/label-only deltas stay row-only.
        Map<String, List<SysModelIndex>> idxModifiedByModel = diff.modelIndexes().modified().stream()
                .filter(m -> ddlRelevantIndexChange(m.fromCode(), m.fromDb()))
                .map(Modification::fromCode)
                .collect(Collectors.groupingBy(SysModelIndex::getModelName));

        // Models added → CREATE TABLE (all-added fields + indexes for this model)
        Map<String, ModelOps> ops = new LinkedHashMap<>();
        for (SysModel added : diff.models().added()) {
            if (isProjection(added)) {
                // A projection owns no DDL — its table belongs to another model or an external
                // process. Consume its buckets so the ALTER aggregation below cannot resurrect
                // them as ADD COLUMN / ADD INDEX units; the sys_* rows are still written.
                fieldsAddedByModel.remove(added.getModelName());
                idxAddedByModel.remove(added.getModelName());
                continue;
            }
            List<SysField> modelFields = fieldsAddedByModel.getOrDefault(added.getModelName(), List.of());
            List<SysModelIndex> modelIndexes = idxAddedByModel.getOrDefault(added.getModelName(), List.of());
            ops.put(added.getModelName(), new ModelOps(
                    added, Operation.CREATE_TABLE,
                    FieldOps.EMPTY, IndexOps.EMPTY,
                    modelFields, modelIndexes));
            // Consumed by the CREATE; remove from the ALTER buckets.
            fieldsAddedByModel.remove(added.getModelName());
            idxAddedByModel.remove(added.getModelName());
        }

        // Models removed → DROP TABLE WARNING (orchestrator logs only)
        for (SysModel removed : diff.models().removed()) {
            if (isProjection(removed)) {
                // No DROP TABLE hint: the table belongs to its owner and must survive the
                // projection's removal — hinting a DROP here would invite deleting it.
                fieldsRemovedByModel.remove(removed.getModelName());
                idxRemovedByModel.remove(removed.getModelName());
                continue;
            }
            ops.put(removed.getModelName(), new ModelOps(
                    removed, Operation.DROP_TABLE_WARNING,
                    new FieldOps(List.of(), List.of(), List.of(),
                            fieldsRemovedByModel.getOrDefault(removed.getModelName(), List.of())),
                    new IndexOps(List.of(), List.of(),
                            idxRemovedByModel.getOrDefault(removed.getModelName(), List.of())),
                    List.of(), List.of()));
            fieldsRemovedByModel.remove(removed.getModelName());
            idxRemovedByModel.remove(removed.getModelName());
        }

        // Models modified — model-level attr changes don't drive DDL here;
        // their field + index changes are handled by the ALTER aggregation below.
        Map<String, SysModel> modifiedModelByName = diff.models().modified().stream()
                .map(Modification::fromCode)
                .collect(Collectors.toMap(SysModel::getModelName, Function.identity(), (a, b) -> a));

        // Aggregate field + index changes per model not already a CREATE/DROP
        Set<String> alterModelNames = new LinkedHashSet<>();
        alterModelNames.addAll(fieldsAddedByModel.keySet());
        alterModelNames.addAll(fieldsModifiedByModel.keySet());
        alterModelNames.addAll(fieldsRenamedByModel.keySet());
        alterModelNames.addAll(fieldsRemovedByModel.keySet());
        alterModelNames.addAll(idxAddedByModel.keySet());
        alterModelNames.addAll(idxModifiedByModel.keySet());
        alterModelNames.addAll(idxRemovedByModel.keySet());

        for (String modelName : alterModelNames) {
            if (ops.containsKey(modelName)) {
                continue;   // already handled by CREATE or DROP
            }
            List<SysField> added = fieldsAddedByModel.getOrDefault(modelName, List.of());
            List<SysField> updated = fieldsModifiedByModel.getOrDefault(modelName, List.of());
            List<FieldRename> renamed = fieldsRenamedByModel.getOrDefault(modelName, List.of());
            List<SysField> removedFields = fieldsRemovedByModel.getOrDefault(modelName, List.of());

            List<SysModelIndex> idxAdded = idxAddedByModel.getOrDefault(modelName, List.of());
            List<SysModelIndex> idxUpdated = idxModifiedByModel.getOrDefault(modelName, List.of());
            List<SysModelIndex> idxRemoved = idxRemovedByModel.getOrDefault(modelName, List.of());

            SysModel modelRef = modifiedModelByName.get(modelName);
            if (modelRef == null) {
                modelRef = allCodeModels.get(modelName);
            }
            if (modelRef == null) {
                modelRef = new SysModel();
                modelRef.setModelName(modelName);
            }
            if (isProjection(modelRef)) {
                continue;   // field / index changes on a projection are row-only — never DDL
            }
            boolean hasDrops = !removedFields.isEmpty() || !idxRemoved.isEmpty();
            Operation op = hasDrops ? Operation.ALTER_TABLE_WITH_DROP_WARNING : Operation.ALTER_TABLE;
            ops.put(modelName, new ModelOps(modelRef, op,
                    new FieldOps(added, updated, renamed, removedFields),
                    new IndexOps(idxAdded, idxUpdated, idxRemoved),
                    List.of(), List.of()));
        }

        return new ArrayList<>(ops.values());
    }

    /**
     * A projection model owns no DDL — its table belongs to another model or an
     * external process ({@code @Model(projection = true)}).
     */
    private static boolean isProjection(SysModel model) {
        return model != null && Boolean.TRUE.equals(model.getProjection());
    }

    // ---- DDL-relevant projection ---------------------------------------

    /**
     * Whether the code→db delta touches anything the rendered column depends on:
     * physical type (the {@code relatedFieldType} mirror for a TO_ONE FK, else
     * {@code fieldType}), length / scale, NOT NULL, DEFAULT, or the description
     * (rendered as COMMENT). Everything else on {@code sys_field} is row-only.
     */
    private static boolean ddlRelevantFieldChange(SysField code, SysField db) {
        return physicalType(code) != physicalType(db)
                || !Objects.equals(code.getLength(), db.getLength())
                || !Objects.equals(code.getScale(), db.getScale())
                || isTrue(code.getRequired()) != isTrue(db.getRequired())
                || !Objects.equals(code.getDefaultValue(), db.getDefaultValue())
                || !Objects.equals(code.getDescription(), db.getDescription());
    }

    /** The type the column renders as — the resolved FK mirror when present. */
    private static FieldType physicalType(SysField f) {
        return SysDdlContextBuilder.resolvePhysicalFieldType(f);
    }

    /**
     * Whether the index delta changes the physical index: its columns, its uniqueness, or its
     * access method. {@code message} (violation text) and other row attributes never justify a
     * rebuild.
     *
     * <p>The index type is compared NORMALIZED. Rows written before {@code index_type} existed read
     * back null, and treating null as different from {@code BTREE} would rebuild every index in the
     * catalog on the first boot after the column is added.
     */
    private static boolean ddlRelevantIndexChange(SysModelIndex code, SysModelIndex db) {
        return !Objects.equals(code.getIndexFields(), db.getIndexFields())
                || isTrue(code.getUniqueIndex()) != isTrue(db.getUniqueIndex())
                || indexType(code) != indexType(db);
    }

    private static IndexType indexType(SysModelIndex idx) {
        return idx.getIndexType() == null ? IndexType.BTREE : idx.getIndexType();
    }

    private static boolean isTrue(Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    private static String effectiveColumnName(SysField f) {
        return SysDdlContextBuilder.resolveColumnName(f);
    }

    private static <K, V> List<V> bucket(Map<K, List<V>> map, K key) {
        return map.computeIfAbsent(key, k -> new ArrayList<>());
    }

    private static Map<String, List<SysModelIndex>> groupIndexesByModel(List<SysModelIndex> indexes) {
        Map<String, List<SysModelIndex>> out = new HashMap<>();
        for (SysModelIndex idx : indexes) {
            bucket(out, idx.getModelName()).add(idx);
        }
        return out;
    }

    private static Map<String, List<SysField>> groupByModel(List<SysField> fields) {
        Map<String, List<SysField>> out = new HashMap<>();
        for (SysField f : fields) {
            bucket(out, f.getModelName()).add(f);
        }
        return out;
    }
}
