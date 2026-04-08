package io.softa.starter.studio.template.ddl.context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Mutable accumulator for assembling model-level DDL context from row-level changes.
 */
public final class ModelDdlAccumulator {

    private final Map<String, ModelDdlCtx> createdModels = new LinkedHashMap<>();
    private final Map<String, ModelDdlCtx> deletedModels = new LinkedHashMap<>();
    private final Map<String, ModelDdlCtx> updatedModels = new LinkedHashMap<>();

    public void addCreatedModel(ModelDdlCtx model) {
        createdModels.put(model.getModelName(), model);
    }

    public void addDeletedModel(ModelDdlCtx model) {
        deletedModels.put(model.getModelName(), model);
    }

    public void mergeUpdatedModel(ModelDdlCtx currentModel, ModelDdlCtx previousModel) {
        String modelName = currentModel.getModelName();
        if (createdModels.containsKey(modelName) || deletedModels.containsKey(modelName)) {
            return;
        }
        ModelDdlCtx targetModel = updatedModels.computeIfAbsent(modelName, ignored -> currentModel);
        targetModel.setLabelName(currentModel.getLabelName());
        targetModel.setDescription(currentModel.getDescription());
        targetModel.setTableName(currentModel.getTableName());
        targetModel.setPkColumn(currentModel.getPkColumn());
        targetModel.setIdStrategy(currentModel.getIdStrategy());
        targetModel.setAutoIncrementPrimaryKey(currentModel.isAutoIncrementPrimaryKey());
        if (!Objects.equals(previousModel.getTableName(), currentModel.getTableName())) {
            targetModel.setOldTableName(previousModel.getTableName());
            targetModel.setRenamed(true);
        }
        if (!Objects.equals(previousModel.getDescription(), currentModel.getDescription())) {
            targetModel.setDescriptionChanged(true);
        }
    }

    public void addCreatedField(String modelName, FieldDdlCtx field) {
        if (deletedModels.containsKey(modelName)) {
            return;
        }
        targetModel(modelName).getCreatedFields().add(field);
    }

    public void addDeletedField(String modelName, FieldDdlCtx field) {
        if (createdModels.containsKey(modelName) || deletedModels.containsKey(modelName)) {
            return;
        }
        updatedModel(modelName).getDeletedFields().add(field);
    }

    public void addUpdatedField(
            String modelName, FieldDdlCtx currentField, FieldDdlCtx previousField, boolean storedBefore, boolean storedAfter) {
        if (createdModels.containsKey(modelName) || deletedModels.containsKey(modelName)) {
            return;
        }
        ModelDdlCtx model = updatedModel(modelName);
        if (storedBefore && !storedAfter) {
            model.getDeletedFields().add(previousField);
            return;
        }
        if (!storedBefore && storedAfter) {
            model.getCreatedFields().add(currentField);
            return;
        }
        if (!storedBefore) {
            return;
        }
        if (!Objects.equals(previousField.getColumnName(), currentField.getColumnName())) {
            currentField.setOldColumnName(previousField.getColumnName());
            currentField.setRenamed(true);
            model.getRenamedFields().add(currentField);
            return;
        }
        model.getUpdatedFields().add(currentField);
    }

    public void addCreatedIndex(String modelName, IndexDdlCtx index) {
        if (deletedModels.containsKey(modelName)) {
            return;
        }
        targetModel(modelName).getCreatedIndexes().add(index);
    }

    public void addDeletedIndex(String modelName, IndexDdlCtx index) {
        if (createdModels.containsKey(modelName) || deletedModels.containsKey(modelName)) {
            return;
        }
        updatedModel(modelName).getDeletedIndexes().add(index);
    }

    public void addUpdatedIndex(String modelName, IndexDdlCtx currentIndex, IndexDdlCtx previousIndex) {
        if (createdModels.containsKey(modelName) || deletedModels.containsKey(modelName)) {
            return;
        }
        ModelDdlCtx model = updatedModel(modelName);
        if (!Objects.equals(previousIndex.getIndexName(), currentIndex.getIndexName())) {
            currentIndex.setOldIndexName(previousIndex.getIndexName());
            currentIndex.setRenamed(true);
            currentIndex.setDefinitionChanged(!Objects.equals(previousIndex.getColumns(), currentIndex.getColumns())
                    || previousIndex.isUnique() != currentIndex.isUnique());
            model.getRenamedIndexes().add(currentIndex);
            return;
        }
        model.getUpdatedIndexes().add(currentIndex);
    }

    public DdlTemplateContext toTemplateContext() {
        DdlTemplateContext context = new DdlTemplateContext();
        context.getCreatedModels().addAll(createdModels.values());
        context.getDeletedModels().addAll(deletedModels.values());
        context.getUpdatedModels().addAll(updatedModels.values());
        return context;
    }

    private ModelDdlCtx targetModel(String modelName) {
        ModelDdlCtx createdModel = createdModels.get(modelName);
        return createdModel != null ? createdModel : updatedModel(modelName);
    }

    private ModelDdlCtx updatedModel(String modelName) {
        return updatedModels.computeIfAbsent(modelName, DdlContextBuilder::placeholderModel);
    }
}
