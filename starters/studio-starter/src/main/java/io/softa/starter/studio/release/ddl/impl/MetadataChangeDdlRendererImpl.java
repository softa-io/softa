package io.softa.starter.studio.release.ddl.impl;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

import io.softa.starter.metadata.ddl.context.*;
import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.studio.release.ddl.DdlRenderResult;
import io.softa.starter.studio.release.ddl.MetadataChangeDdlRenderer;
import io.softa.starter.studio.release.dto.DesignMetaTables;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.ddl.context.DdlContextBuilder;

/**
 * {@link MetadataChangeDdlRenderer} implementation.
 */
@Component
public class MetadataChangeDdlRendererImpl implements MetadataChangeDdlRenderer {

    /** Properties related to table metadata. */
    private static final Set<String> MODEL_PROPERTIES = Set.of("tableName", "description");
    /** Properties related to database columns in field metadata. */
    private static final Set<String> COLUMN_PROPERTIES = Set.of(
            "columnName", "fieldName", "fieldType", "relatedModel", "relatedField", "relatedFieldType",
            "length", "scale", "required", "defaultValue", "description", "dynamic"
    );
    private static final Set<String> INDEX_PROPERTIES = Set.of("indexName", "indexFields", "uniqueIndex");

    @Override
    public DdlRenderResult generateDdlResult(DdlDialect dialect, List<RowChangeDTO> changes) {
        if (changes == null || changes.isEmpty()) {
            return new DdlRenderResult("", "");
        }
        // The diff is a flat row-change list — regroup per meta-table + op for rendering.
        ChangeBundle changeBundle = resolveChangeBundle(DesignMetaTables.group(changes));
        DdlTemplateContext context = buildTemplateContext(
                changeBundle.modelChanges(), changeBundle.fieldChanges(), changeBundle.indexChanges());
        return new DdlRenderResult(
                renderTableDDL(dialect, context),
                renderIndexDDL(dialect, context)
        );
    }

    private ChangeBundle resolveChangeBundle(List<ModelChangesDTO> mergedChanges) {
        ModelChangesDTO modelChanges = null;
        ModelChangesDTO fieldChanges = null;
        ModelChangesDTO indexChanges = null;
        for (ModelChangesDTO dto : mergedChanges) {
            switch (dto.getModelName()) {
                case "DesignModel" -> modelChanges = dto;
                case "DesignField" -> fieldChanges = dto;
                case "DesignModelIndex" -> indexChanges = dto;
            }
        }
        return new ChangeBundle(modelChanges, fieldChanges, indexChanges);
    }

    private DdlTemplateContext buildTemplateContext(ModelChangesDTO modelChanges, ModelChangesDTO fieldChanges,
                                                    ModelChangesDTO indexChanges) {
        ModelDdlAccumulator accumulator = new ModelDdlAccumulator();
        applyModelChanges(modelChanges, accumulator);
        applyFieldChanges(fieldChanges, accumulator);
        applyIndexChanges(indexChanges, accumulator);
        return accumulator.toTemplateContext();
    }

    private void applyModelChanges(ModelChangesDTO modelChanges, ModelDdlAccumulator accumulator) {
        if (modelChanges == null) {
            return;
        }
        // Order matters: created + deleted are accumulated before updated, since mergeUpdatedModel
        // reconciles an update against the models already collected.
        for (RowChangeDTO rowChangeDTO : modelChanges.getCreatedRows()) {
            accumulator.addCreatedModel(DdlContextBuilder.fromModelData(rowChangeDTO.getFullRow()));
        }
        for (RowChangeDTO rowChangeDTO : modelChanges.getDeletedRows()) {
            accumulator.addDeletedModel(DdlContextBuilder.fromModelData(rowChangeDTO.getFullRow()));
        }
        for (RowChangeDTO rowChangeDTO : modelChanges.getUpdatedRows()) {
            if (Collections.disjoint(MODEL_PROPERTIES, rowChangeDTO.getPreviousValuesForChangedFields().keySet())) {
                continue;
            }
            ModelDdlCtx currentModel = DdlContextBuilder.fromModelData(rowChangeDTO.getFullRow());
            ModelDdlCtx previousModel = DdlContextBuilder.fromModelData(DdlContextBuilder.buildPreviousData(rowChangeDTO));
            accumulator.mergeUpdatedModel(currentModel, previousModel);
        }
    }

    private void applyFieldChanges(ModelChangesDTO fieldChanges, ModelDdlAccumulator accumulator) {
        if (fieldChanges == null) {
            return;
        }
        for (RowChangeDTO rowChangeDTO : fieldChanges.getCreatedRows()) {
            Map<String, Object> currentData = rowChangeDTO.getFullRow();
            String modelName = asString(currentData.get("modelName"));
            if (!isStoredField(currentData)) {
                continue;
            }
            FieldDdlCtx currentField = DdlContextBuilder.fromFieldData(
                    currentData, resolvePkColumn(currentData, modelName, accumulator),
                    isAutoIncrementPrimaryKey(currentData, modelName, accumulator));
            accumulator.addCreatedField(modelName, currentField);
        }
        for (RowChangeDTO rowChangeDTO : fieldChanges.getDeletedRows()) {
            Map<String, Object> currentData = rowChangeDTO.getFullRow();
            String modelName = asString(currentData.get("modelName"));
            if (!isStoredField(currentData)) {
                continue;
            }
            FieldDdlCtx previousField = DdlContextBuilder.fromFieldData(
                    currentData, resolvePkColumn(currentData, modelName, accumulator),
                    isAutoIncrementPrimaryKey(currentData, modelName, accumulator));
            accumulator.addDeletedField(modelName, previousField);
        }
        for (RowChangeDTO rowChangeDTO : fieldChanges.getUpdatedRows()) {
            if (Collections.disjoint(COLUMN_PROPERTIES, rowChangeDTO.getPreviousValuesForChangedFields().keySet())) {
                continue;
            }
            Map<String, Object> currentData = rowChangeDTO.getFullRow();
            String modelName = asString(currentData.get("modelName"));
            Map<String, Object> previousData = DdlContextBuilder.buildPreviousData(rowChangeDTO);
            boolean storedBefore = isStoredField(previousData);
            boolean storedAfter = isStoredField(currentData);
            FieldDdlCtx currentField = DdlContextBuilder.fromFieldData(
                    currentData, resolvePkColumn(currentData, modelName, accumulator),
                    isAutoIncrementPrimaryKey(currentData, modelName, accumulator));
            FieldDdlCtx previousField = DdlContextBuilder.fromFieldData(
                    previousData, resolvePkColumn(previousData, modelName, accumulator),
                    isAutoIncrementPrimaryKey(previousData, modelName, accumulator));
            accumulator.addUpdatedField(modelName, currentField, previousField, storedBefore, storedAfter);
        }
    }

    private void applyIndexChanges(ModelChangesDTO indexChanges, ModelDdlAccumulator accumulator) {
        if (indexChanges == null) {
            return;
        }
        for (RowChangeDTO rowChangeDTO : indexChanges.getCreatedRows()) {
            Map<String, Object> currentData = rowChangeDTO.getFullRow();
            String modelName = asString(currentData.get("modelName"));
            accumulator.addCreatedIndex(modelName, DdlContextBuilder.fromIndexData(currentData));
        }
        for (RowChangeDTO rowChangeDTO : indexChanges.getDeletedRows()) {
            Map<String, Object> currentData = rowChangeDTO.getFullRow();
            String modelName = asString(currentData.get("modelName"));
            accumulator.addDeletedIndex(modelName, DdlContextBuilder.fromIndexData(currentData));
        }
        for (RowChangeDTO rowChangeDTO : indexChanges.getUpdatedRows()) {
            if (Collections.disjoint(INDEX_PROPERTIES, rowChangeDTO.getPreviousValuesForChangedFields().keySet())) {
                continue;
            }
            Map<String, Object> currentData = rowChangeDTO.getFullRow();
            String modelName = asString(currentData.get("modelName"));
            Map<String, Object> previousData = DdlContextBuilder.buildPreviousData(rowChangeDTO);
            IndexDdlCtx currentIndex = DdlContextBuilder.fromIndexData(currentData);
            IndexDdlCtx previousIndex = DdlContextBuilder.fromIndexData(previousData);
            accumulator.addUpdatedIndex(modelName, currentIndex, previousIndex);
        }
    }

    private String renderTableDDL(DdlDialect dialect, DdlTemplateContext context) {
        StringBuilder ddl = new StringBuilder();
        appendCreatedTableDDL(dialect, ddl, context.getCreatedModels());
        appendDeletedTableDDL(dialect, ddl, context.getDeletedModels());
        appendUpdatedTableDDL(dialect, ddl, context.getUpdatedModels());
        return ddl.toString();
    }

    private void appendCreatedTableDDL(DdlDialect dialect, StringBuilder ddl, List<ModelDdlCtx> createdModels) {
        List<ModelDdlCtx> models = createdModels.stream()
                .filter(model -> !model.getCreatedFields().isEmpty())
                .toList();
        if (models.isEmpty()) {
            return;
        }
        ddl.append("-- Create tables:\n");
        for (ModelDdlCtx model : models) {
            ddl.append(dialect.createTableDDL(model)).append("\n\n");
        }
    }

    private void appendDeletedTableDDL(DdlDialect dialect, StringBuilder ddl, List<ModelDdlCtx> deletedModels) {
        if (deletedModels.isEmpty()) {
            return;
        }
        ddl.append("-- Delete tables:\n");
        for (ModelDdlCtx model : deletedModels) {
            ddl.append(dialect.dropTableDDL(model)).append("\n");
        }
        ddl.append("\n");
    }

    private void appendUpdatedTableDDL(DdlDialect dialect, StringBuilder ddl, List<ModelDdlCtx> updatedModels) {
        List<ModelDdlCtx> models = updatedModels.stream()
                .filter(model -> model.isRenamed() || model.isHasAlterTableChanges())
                .toList();
        if (models.isEmpty()) {
            return;
        }
        ddl.append("-- Alter tables:\n");
        for (ModelDdlCtx model : models) {
            ddl.append(dialect.alterTableDDL(model)).append("\n\n");
        }
    }

    private String renderIndexDDL(DdlDialect dialect, DdlTemplateContext context) {
        StringBuilder ddl = new StringBuilder();
        appendUpdatedIndexDDL(dialect, ddl, context.getUpdatedModels());
        return ddl.toString();
    }

    private void appendUpdatedIndexDDL(DdlDialect dialect, StringBuilder ddl, List<ModelDdlCtx> updatedModels) {
        List<ModelDdlCtx> models = updatedModels.stream()
                .filter(ModelDdlCtx::isHasIndexChanges)
                .toList();
        if (models.isEmpty()) {
            return;
        }
        ddl.append("-- Alter table indexes:\n");
        for (ModelDdlCtx model : models) {
            ddl.append(dialect.alterIndexDDL(model)).append("\n\n");
        }
    }

    private boolean isStoredField(Map<String, Object> data) {
        return DdlContextBuilder.isStoredField(
                DdlContextBuilder.asFieldType(data.get("fieldType")),
                DdlContextBuilder.asBoolean(data.get("dynamic")));
    }

    /**
     * Resolve the primary key column name for this field's owning model.
     * <p>
     * Field change records do not carry the model's {@code timeline} flag, so we first
     * consult the accumulator (which holds the model context when the model itself is
     * being created / renamed in the same change set) before falling back to the field
     * data (which for standalone field changes must be assumed {@code timeline = false}).
     */
    private String resolvePkColumn(Map<String, Object> data, String modelName, ModelDdlAccumulator accumulator) {
        ModelDdlCtx model = modelName == null ? null : accumulator.findModel(modelName);
        if (model != null && model.getPkColumn() != null) {
            return model.getPkColumn();
        }
        return DdlContextBuilder.resolvePkColumn(DdlContextBuilder.asBoolean(data.get("timeline")));
    }

    /**
     * Resolve whether the field's owning model uses an auto-increment primary key.
     * <p>
     * Same caveat as {@link #resolvePkColumn}: field change records do not carry the
     * model's {@code idStrategy}, so we delegate to the accumulator whenever possible.
     */
    private boolean isAutoIncrementPrimaryKey(Map<String, Object> data, String modelName,
                                              ModelDdlAccumulator accumulator) {
        ModelDdlCtx model = modelName == null ? null : accumulator.findModel(modelName);
        if (model != null && model.getIdStrategy() != null) {
            return model.isAutoIncrementPrimaryKey();
        }
        return DdlContextBuilder.isAutoIncrementPrimaryKey(DdlContextBuilder.asIdStrategy(data.get("idStrategy")));
    }

    private String asString(Object value) {
        return DdlContextBuilder.asString(value);
    }

    private record ChangeBundle(
            ModelChangesDTO modelChanges,
            ModelChangesDTO fieldChanges,
            ModelChangesDTO indexChanges
    ) {}
}
