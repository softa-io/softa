package io.softa.starter.metadata.ddl;

import java.util.*;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.IndexType;
import io.softa.starter.metadata.ddl.context.FieldDdlCtx;
import io.softa.starter.metadata.ddl.context.IndexDdlCtx;
import io.softa.starter.metadata.ddl.context.ModelDdlCtx;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;

/**
 * Builds {@link ModelDdlCtx} from {@link SysModel} + {@link SysField} entities
 * for the metadata-starter scanner path.
 *
 * <p>Mirror of studio-starter's {@code DdlContextBuilder} (which builds from
 * {@code DesignModel} / {@code DesignField}). Pure POJO — no Spring deps.
 * The two builders intentionally don't share a base class: each is ~100 lines
 * of straightforward setter mapping, and the source-type evolution may diverge.
 */
@Slf4j
public final class SysDdlContextBuilder {

    private SysDdlContextBuilder() {}

    /**
     * Build a CREATE-TABLE context. The PK column ({@code id}, or {@code sliceId}
     * for a timeline model) arrives as a real field in {@code allFields} — the
     * parser always emits it. {@code createdIndexes} is populated from
     * {@code allIndexes} filtered to this model.
     */
    public static ModelDdlCtx forCreate(
            SysModel model,
            List<SysField> allFields,
            List<SysModelIndex> allIndexes) {
        ModelDdlCtx ctx = baseModel(model);
        // Build field→column map for index translation
        Map<String, String> fieldToColumn = new HashMap<>();
        allFields.stream()
                .filter(f -> isStored(f) && Objects.equals(f.getModelName(), model.getModelName()))
                .forEach(f -> {
                    FieldDdlCtx fc = toFieldCtx(f, ctx.getPkColumn(), ctx.isAutoIncrementPrimaryKey());
                    ctx.getCreatedFields().add(fc);
                    fieldToColumn.put(f.getFieldName(), fc.getColumnName());
                });
        if (allIndexes != null) {
            allIndexes.stream()
                    .filter(idx -> Objects.equals(idx.getModelName(), model.getModelName()))
                    .map(idx -> toIndexCtx(idx, fieldToColumn))
                    .forEach(ctx.getCreatedIndexes()::add);
        }
        return ctx;
    }

    /**
     * Build an ALTER-INDEX context: index changes drive
     * {@code createdIndexes} / {@code deletedIndexes} / {@code updatedIndexes}.
     * Index column names are resolved via the supplied
     * {@code fieldToColumn} map (built by the orchestrator from the model's
     * full field set).
     */
    public static ModelDdlCtx forIndexChanges(
            SysModel model,
            Map<String, String> fieldToColumn,
            List<SysModelIndex> addedIndexes,
            List<SysModelIndex> updatedIndexes,
            List<SysModelIndex> deletedIndexes) {
        ModelDdlCtx ctx = baseModel(model);
        addedIndexes.stream()
                .map(idx -> toIndexCtx(idx, fieldToColumn))
                .forEach(ctx.getCreatedIndexes()::add);
        updatedIndexes.stream()
                .map(idx -> toIndexCtx(idx, fieldToColumn))
                .forEach(ctx.getUpdatedIndexes()::add);
        deletedIndexes.stream()
                .map(idx -> toIndexCtx(idx, fieldToColumn))
                .forEach(ctx.getDeletedIndexes()::add);
        return ctx;
    }

    private static IndexDdlCtx toIndexCtx(SysModelIndex idx,
                                          Map<String, String> fieldToColumn) {
        IndexDdlCtx ctx = new IndexDdlCtx();
        ctx.setIndexName(idx.getIndexName());
        ctx.setUnique(Boolean.TRUE.equals(idx.getUniqueIndex()));
        // Null = BTREE: rows written before index_type existed must keep rendering as they did.
        ctx.setIndexType(idx.getIndexType() == null ? IndexType.BTREE : idx.getIndexType());
        if (idx.getIndexFields() != null) {
            List<String> columns = new ArrayList<>(idx.getIndexFields().size());
            for (String fieldName : idx.getIndexFields()) {
                String columnName = fieldToColumn.get(fieldName);
                columns.add(columnName != null ? columnName : StringTools.toUnderscoreCase(fieldName));
            }
            ctx.setColumns(columns);
        }
        return ctx;
    }

    /**
     * Build an ALTER-TABLE context: caller pre-classifies fields into the four
     * buckets (created / updated / renamed / deleted), each list is fed to the
     * matching slot on {@link ModelDdlCtx}. A {@link DdlPolicy.FieldRename}
     * carries the new-name field plus the column it currently occupies, so the
     * dialect renders {@code CHANGE COLUMN old new ...}.
     */
    public static ModelDdlCtx forAlter(
            SysModel model,
            List<SysField> addedFields,
            List<SysField> updatedFields,
            List<DdlPolicy.FieldRename> renamedFields,
            List<SysField> deletedFields) {
        ModelDdlCtx ctx = baseModel(model);
        addedFields.stream()
                .filter(SysDdlContextBuilder::isStored)
                .map(f -> toFieldCtx(f, ctx.getPkColumn(), ctx.isAutoIncrementPrimaryKey()))
                .forEach(ctx.getCreatedFields()::add);
        updatedFields.stream()
                .filter(SysDdlContextBuilder::isStored)
                .map(f -> toFieldCtx(f, ctx.getPkColumn(), ctx.isAutoIncrementPrimaryKey()))
                .forEach(ctx.getUpdatedFields()::add);
        renamedFields.stream()
                .filter(r -> isStored(r.field()))
                .map(r -> {
                    FieldDdlCtx fc = toFieldCtx(r.field(), ctx.getPkColumn(), ctx.isAutoIncrementPrimaryKey());
                    fc.setOldColumnName(r.oldColumnName());
                    fc.setRenamed(true);
                    return fc;
                })
                .forEach(ctx.getRenamedFields()::add);
        deletedFields.stream()
                .filter(SysDdlContextBuilder::isStored)
                .map(f -> toFieldCtx(f, ctx.getPkColumn(), ctx.isAutoIncrementPrimaryKey()))
                .forEach(ctx.getDeletedFields()::add);
        return ctx;
    }

    /**
     * Build a DROP-TABLE context: minimal context with just modelName +
     * tableName. The {@code DropTable.peb} template only needs these.
     */
    public static ModelDdlCtx forDrop(SysModel model) {
        return baseModel(model);
    }

    private static ModelDdlCtx baseModel(SysModel model) {
        ModelDdlCtx ctx = new ModelDdlCtx();
        ctx.setModelName(model.getModelName());
        ctx.setLabel(model.getLabel());
        ctx.setDescription(model.getDescription());
        ctx.setTableName(resolveTableName(model));
        ctx.setIdStrategy(model.getIdStrategy());
        ctx.setAutoIncrementPrimaryKey(isAutoIncrementPrimaryKey(model.getIdStrategy()));
        ctx.setPkColumn(resolvePkColumn(Boolean.TRUE.equals(model.getTimeline())));
        return ctx;
    }

    private static FieldDdlCtx toFieldCtx(SysField field, String pkColumn, boolean autoIncrementPk) {
        FieldDdlCtx ctx = new FieldDdlCtx();
        ctx.setFieldName(field.getFieldName());
        ctx.setColumnName(resolveColumnName(field));
        ctx.setLabel(field.getLabel());
        ctx.setDescription(field.getDescription());
        // For a TO_ONE FK the physical type was resolved into relatedFieldType (+ length/scale)
        // at reconciliation time; render it directly. fieldType (the logical relation type) is
        // only stored, not rendered.
        ctx.setFieldType(field.getRelatedFieldType() != null ? field.getRelatedFieldType() : field.getFieldType());
        ctx.setLength(field.getLength());
        ctx.setScale(field.getScale());
        ctx.setRequired(Boolean.TRUE.equals(field.getRequired()));
        ctx.setAutoIncrement(autoIncrementPk
                && pkColumn != null
                && pkColumn.equals(ctx.getColumnName()));
        ctx.setDefaultValue(field.getDefaultValue());
        return ctx;
    }

    /** Blank tableName means derived: snake_case(modelName). The single source of this rule. */
    public static String resolveTableName(SysModel model) {
        if (model.getTableName() != null && !model.getTableName().isBlank()) {
            return model.getTableName();
        }
        return StringTools.toUnderscoreCase(model.getModelName());
    }

    /** Blank columnName means derived: snake_case(fieldName). The single source of this rule. */
    public static String resolveColumnName(SysField field) {
        if (field.getColumnName() != null && !field.getColumnName().isBlank()) {
            return field.getColumnName();
        }
        return StringTools.toUnderscoreCase(field.getFieldName());
    }

    /**
     * The type the column physically renders as — the system-resolved TO_ONE FK mirror
     * ({@code relatedFieldType}) when present, else the declared {@code fieldType}. The single
     * source of this rule ({@link DdlPolicy} and the physical type comparison both use it).
     */
    public static FieldType resolvePhysicalFieldType(SysField field) {
        return field.getRelatedFieldType() != null ? field.getRelatedFieldType() : field.getFieldType();
    }

    private static String resolvePkColumn(boolean timeline) {
        return timeline ? ModelConstant.SLICE_ID_COLUMN : ModelConstant.ID;
    }

    private static boolean isAutoIncrementPrimaryKey(IdStrategy idStrategy) {
        return idStrategy == IdStrategy.DB_AUTO_ID;
    }

    /**
     * Stored = actually persisted as a column (not a transient relation type
     * nor a dynamic computed field). Public: {@link DdlPolicy} routes
     * stored↔non-stored transitions to ADD / DROP with the same rule, and the
     * physical-drift auditor uses it to expect columns only for stored fields.
     */
    public static boolean isStored(SysField field) {
        if (field == null || field.getFieldType() == null) {
            return false;
        }
        if (Boolean.TRUE.equals(field.getDynamic())) {
            return false;
        }
        return !FieldType.TO_MANY_TYPES.contains(field.getFieldType());
    }
}
