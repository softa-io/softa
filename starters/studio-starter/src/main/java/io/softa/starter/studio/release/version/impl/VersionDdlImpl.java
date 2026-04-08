package io.softa.starter.studio.release.version.impl;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.version.VersionDdl;
import io.softa.starter.studio.release.version.VersionDdlResult;
import io.softa.starter.studio.template.ddl.context.*;
import io.softa.starter.studio.template.ddl.dialect.DdlDialect;
import io.softa.starter.studio.template.ddl.dialect.DdlDialectRegistry;

/**
 * VersionDdl implementation.
 */
@Component
public class VersionDdlImpl implements VersionDdl {

    /** Properties related to table metadata. */
    private static final Set<String> MODEL_PROPERTIES = Set.of("tableName", "description");
    /** Properties related to database columns in field metadata. */
    private static final Set<String> COLUMN_PROPERTIES = Set.of(
            "columnName", "fieldName", "fieldType", "length", "scale",
            "required", "defaultValue", "description", "dynamic"
    );
    private static final Set<String> INDEX_PROPERTIES = Set.of("indexName", "indexFields", "uniqueIndex");
    private final DdlDialectRegistry ddlDialectRegistry;

    public VersionDdlImpl(DdlDialectRegistry ddlDialectRegistry) {
        this.ddlDialectRegistry = ddlDialectRegistry;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VersionDdlResult generateDdlResult(DatabaseType databaseType, List<ModelChangesDTO> mergedChanges) {
        if (mergedChanges == null || mergedChanges.isEmpty()) {
            return new VersionDdlResult("", "");
        }
        ChangeBundle changeBundle = resolveChangeBundle(mergedChanges);
        DdlTemplateContext context = buildTemplateContext(
                changeBundle.modelChanges(), changeBundle.fieldChanges(), changeBundle.indexChanges());
        return new VersionDdlResult(
                renderTableDDL(databaseType, context),
                renderIndexDDL(databaseType, context)
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String generateDDL(DatabaseType databaseType, List<ModelChangesDTO> mergedChanges) {
        return generateDdlResult(databaseType, mergedChanges).combinedDdl();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String generateTableDDL(DatabaseType databaseType, ModelChangesDTO modelChanges, ModelChangesDTO fieldChanges) {
        DdlTemplateContext context = buildTemplateContext(modelChanges, fieldChanges, null);
        return renderTableDDL(databaseType, context);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String generateIndexDDL(DatabaseType databaseType, @NotNull ModelChangesDTO indexChanges) {
        if (indexChanges == null) {
            return "";
        }
        DdlTemplateContext context = buildTemplateContext(null, null, indexChanges);
        return renderIndexDDL(databaseType, context);
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
        extractCreatedModels(modelChanges, accumulator);
        extractDeletedModels(modelChanges, accumulator);
        extractUpdatedModels(modelChanges, accumulator);
        applyFieldChanges(fieldChanges, accumulator);
        applyIndexChanges(indexChanges, accumulator);
        return accumulator.toTemplateContext();
    }

    private void extractCreatedModels(ModelChangesDTO modelChanges, ModelDdlAccumulator accumulator) {
        if (modelChanges == null) {
            return;
        }
        for (RowChangeDTO rowChangeDTO : modelChanges.getCreatedRows()) {
            accumulator.addCreatedModel(DdlContextBuilder.fromModelData(rowChangeDTO.getCurrentData()));
        }
    }

    private void extractDeletedModels(ModelChangesDTO modelChanges, ModelDdlAccumulator accumulator) {
        if (modelChanges == null) {
            return;
        }
        for (RowChangeDTO rowChangeDTO : modelChanges.getDeletedRows()) {
            accumulator.addDeletedModel(DdlContextBuilder.fromModelData(rowChangeDTO.getCurrentData()));
        }
    }

    private void extractUpdatedModels(ModelChangesDTO modelChanges, ModelDdlAccumulator accumulator) {
        if (modelChanges == null) {
            return;
        }
        for (RowChangeDTO rowChangeDTO : modelChanges.getUpdatedRows()) {
            if (Collections.disjoint(MODEL_PROPERTIES, rowChangeDTO.getDataAfterChange().keySet())) {
                continue;
            }
            ModelDdlCtx currentModel = DdlContextBuilder.fromModelData(rowChangeDTO.getCurrentData());
            ModelDdlCtx previousModel = DdlContextBuilder.fromModelData(DdlContextBuilder.buildPreviousData(rowChangeDTO));
            accumulator.mergeUpdatedModel(currentModel, previousModel);
        }
    }

    private void applyFieldChanges(ModelChangesDTO fieldChanges, ModelDdlAccumulator accumulator) {
        if (fieldChanges == null) {
            return;
        }
        for (RowChangeDTO rowChangeDTO : fieldChanges.getCreatedRows()) {
            Map<String, Object> currentData = rowChangeDTO.getCurrentData();
            String modelName = asString(currentData.get("modelName"));
            if (!isStoredField(currentData)) {
                continue;
            }
            FieldDdlCtx currentField = DdlContextBuilder.fromFieldData(
                    currentData, resolvePkColumn(currentData), isAutoIncrementPrimaryKey(currentData));
            accumulator.addCreatedField(modelName, currentField);
        }
        for (RowChangeDTO rowChangeDTO : fieldChanges.getDeletedRows()) {
            Map<String, Object> currentData = rowChangeDTO.getCurrentData();
            String modelName = asString(currentData.get("modelName"));
            if (!isStoredField(currentData)) {
                continue;
            }
            FieldDdlCtx previousField = DdlContextBuilder.fromFieldData(
                    currentData, resolvePkColumn(currentData), isAutoIncrementPrimaryKey(currentData));
            accumulator.addDeletedField(modelName, previousField);
        }
        for (RowChangeDTO rowChangeDTO : fieldChanges.getUpdatedRows()) {
            if (Collections.disjoint(COLUMN_PROPERTIES, rowChangeDTO.getDataAfterChange().keySet())) {
                continue;
            }
            Map<String, Object> currentData = rowChangeDTO.getCurrentData();
            String modelName = asString(currentData.get("modelName"));
            Map<String, Object> previousData = DdlContextBuilder.buildPreviousData(rowChangeDTO);
            boolean storedBefore = isStoredField(previousData);
            boolean storedAfter = isStoredField(currentData);
            FieldDdlCtx currentField = DdlContextBuilder.fromFieldData(
                    currentData, resolvePkColumn(currentData), isAutoIncrementPrimaryKey(currentData));
            FieldDdlCtx previousField = DdlContextBuilder.fromFieldData(
                    previousData, resolvePkColumn(previousData), isAutoIncrementPrimaryKey(previousData));
            accumulator.addUpdatedField(modelName, currentField, previousField, storedBefore, storedAfter);
        }
    }

    private void applyIndexChanges(ModelChangesDTO indexChanges, ModelDdlAccumulator accumulator) {
        if (indexChanges == null) {
            return;
        }
        for (RowChangeDTO rowChangeDTO : indexChanges.getCreatedRows()) {
            Map<String, Object> currentData = rowChangeDTO.getCurrentData();
            String modelName = asString(currentData.get("modelName"));
            accumulator.addCreatedIndex(modelName, DdlContextBuilder.fromIndexData(currentData));
        }
        for (RowChangeDTO rowChangeDTO : indexChanges.getDeletedRows()) {
            Map<String, Object> currentData = rowChangeDTO.getCurrentData();
            String modelName = asString(currentData.get("modelName"));
            accumulator.addDeletedIndex(modelName, DdlContextBuilder.fromIndexData(currentData));
        }
        for (RowChangeDTO rowChangeDTO : indexChanges.getUpdatedRows()) {
            if (Collections.disjoint(INDEX_PROPERTIES, rowChangeDTO.getDataAfterChange().keySet())) {
                continue;
            }
            Map<String, Object> currentData = rowChangeDTO.getCurrentData();
            String modelName = asString(currentData.get("modelName"));
            Map<String, Object> previousData = DdlContextBuilder.buildPreviousData(rowChangeDTO);
            IndexDdlCtx currentIndex = DdlContextBuilder.fromIndexData(currentData);
            IndexDdlCtx previousIndex = DdlContextBuilder.fromIndexData(previousData);
            accumulator.addUpdatedIndex(modelName, currentIndex, previousIndex);
        }
    }

    private String renderTableDDL(DatabaseType databaseType, DdlTemplateContext context) {
        DdlDialect dialect = ddlDialectRegistry.getDialect(databaseType);
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

    private String renderIndexDDL(DatabaseType databaseType, DdlTemplateContext context) {
        DdlDialect dialect = ddlDialectRegistry.getDialect(databaseType);
        StringBuilder ddl = new StringBuilder();
        appendUpdatedIndexDDL(dialect, ddl, context.getUpdatedModels());
        appendCreatedIndexDDL(dialect, ddl, context.getCreatedModels());
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

    private void appendCreatedIndexDDL(DdlDialect dialect, StringBuilder ddl, List<ModelDdlCtx> createdModels) {
        List<ModelDdlCtx> models = createdModels.stream()
                .filter(model -> !model.getCreatedIndexes().isEmpty())
                .toList();
        if (models.isEmpty()) {
            return;
        }
        ddl.append("-- Create table indexes:\n");
        for (ModelDdlCtx model : models) {
            ddl.append(dialect.alterIndexDDL(model)).append("\n\n");
        }
    }

    private boolean isStoredField(Map<String, Object> data) {
        return DdlContextBuilder.isStoredField(
                DdlContextBuilder.asFieldType(data.get("fieldType")),
                DdlContextBuilder.asBoolean(data.get("dynamic")));
    }

    private String resolvePkColumn(Map<String, Object> data) {
        return DdlContextBuilder.resolvePkColumn(DdlContextBuilder.asBoolean(data.get("timeline")));
    }

    private boolean isAutoIncrementPrimaryKey(Map<String, Object> data) {
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
