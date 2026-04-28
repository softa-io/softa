package io.softa.starter.metadata.service.impl;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.Cast;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.constant.MetadataConstant;
import io.softa.starter.metadata.controller.dto.MetaFieldDTO;
import io.softa.starter.metadata.controller.dto.MetaModelDTO;
import io.softa.starter.metadata.dto.MetadataUpgradePackage;
import io.softa.starter.metadata.message.InnerBroadcastProducer;
import io.softa.starter.metadata.message.dto.InnerBroadcastMessage;
import io.softa.starter.metadata.message.enums.InnerBroadcastType;
import io.softa.starter.metadata.service.MetadataService;

/**
 * Metadata upgrade service implementation.
 */
@Service
public class MetadataServiceImpl implements MetadataService {

    @Autowired
    private ModelService<Serializable> modelService;

    @Autowired
    private InnerBroadcastProducer innerBroadcastProducer;

    private MetaFieldDTO convertMetaFieldToDTO(MetaField metaField) {
        MetaFieldDTO fieldDTO = new MetaFieldDTO();
        fieldDTO.setLabelName(metaField.getLabelName());
        fieldDTO.setFieldName(metaField.getFieldName());
        fieldDTO.setModelName(metaField.getModelName());
        fieldDTO.setFieldType(metaField.getFieldType());
        fieldDTO.setDescription(metaField.getDescription());
        fieldDTO.setRequired(metaField.isRequired());
        fieldDTO.setLength(metaField.getLength());
        fieldDTO.setScale(metaField.getScale());
        fieldDTO.setDefaultValue(metaField.getDefaultValueObject());
        fieldDTO.setReadonly(metaField.isReadonly());
        fieldDTO.setHidden(metaField.isHidden());
        fieldDTO.setTranslatable(metaField.isTranslatable());
        fieldDTO.setNonCopyable(metaField.isNonCopyable());
        fieldDTO.setUnsearchable(metaField.isUnsearchable());
        fieldDTO.setComputed(metaField.isComputed());
        fieldDTO.setDynamic(metaField.isDynamic());
        fieldDTO.setEncrypted(metaField.isEncrypted());
        fieldDTO.setOptionSetCode(metaField.getOptionSetCode());
        fieldDTO.setRelatedModel(metaField.getRelatedModel());
        fieldDTO.setRelatedField(metaField.getRelatedField());
        fieldDTO.setJoinModel(metaField.getJoinModel());
        fieldDTO.setJoinLeft(metaField.getJoinLeft());
        fieldDTO.setJoinRight(metaField.getJoinRight());
        fieldDTO.setCascadedField(metaField.getCascadedField());
        fieldDTO.setFilters(metaField.getFilters());
        fieldDTO.setMaskingType(metaField.getMaskingType());
        fieldDTO.setWidgetType(metaField.getWidgetType());
        return fieldDTO;
    }

    /**
     * Get the MetaModelDTO object by modelName
     *
     * @param modelName model name
     * @return metaModelDTO object
     */
    @Override
    public MetaModelDTO getMetaModelDTO(String modelName) {
        MetaModel metaModel = ModelManager.getModel(modelName);
        MetaModelDTO metaModelDTO = new MetaModelDTO();
        metaModelDTO.setLabelName(metaModel.getLabelName());
        metaModelDTO.setModelName(metaModel.getModelName());
        metaModelDTO.setDescription(metaModel.getDescription());
        metaModelDTO.setDisplayName(metaModel.getDisplayName());
        metaModelDTO.setSearchName(metaModel.getSearchName());
        metaModelDTO.setDefaultOrder(metaModel.getDefaultOrder());
        metaModelDTO.setTimeline(metaModel.isTimeline());
        // Get the fields of the model and convert them to DTOs
        List<MetaField> metaFields = ModelManager.getModelFields(modelName);
        Map<String, MetaFieldDTO> fieldDTOMap = metaFields.stream().
                collect(Collectors.toMap(MetaField::getFieldName, this::convertMetaFieldToDTO));
        metaModelDTO.setModelFields(fieldDTOMap);
        return metaModelDTO;
    }

    /**
     * The size of operation data in a single API call cannot exceed the MAX_BATCH_SIZE.
     *
     * @param size data size
     */
    private void validateBatchSize(int size) {
        Assert.isTrue(size <= BaseConstant.MAX_BATCH_SIZE,
                "The size of operation data cannot exceed the maximum {0} limit.",
                BaseConstant.MAX_BATCH_SIZE);
    }

    /**
     * Validate if the model is enabled for version control.
     */
    private void validateRuntimeModel(String modelName) {
        Assert.isTrue(MetadataConstant.VERSION_CONTROL_MODELS.containsValue(modelName),
                "Model {0} is not enabled for version control, and the upgrade API cannot be invoked.", modelName);
    }

    /**
     * Create metadata.
     *
     * @param modelName The name of the model
     * @param createRows The list of metadata to be created
     */
    private void createMetadata(String modelName, List<Map<String, Object>> createRows) {
        if (!CollectionUtils.isEmpty(createRows)) {
            this.validateBatchSize(createRows.size());
            this.validateInsertIdEnabled();
            List<Serializable> requestedIds = this.extractIds(createRows, modelName, "create");
            List<Serializable> createdIds = Cast.of(modelService.createList(modelName, createRows));
            Assert.isEqual(createdIds, requestedIds,
                    "When creating metadata of model {0}, the created ids must match the requested ids. requested={1}, actual={2}",
                    modelName, requestedIds, createdIds);
        }
    }

    /**
     * Update metadata by unique id
     *
     * @param modelName The name of the model
     * @param updateRows The list of data to be updated
     */
    private void updateById(String modelName, List<Map<String, Object>> updateRows) {
        if (!CollectionUtils.isEmpty(updateRows)) {
            this.validateBatchSize(updateRows.size());
            List<Serializable> ids = this.extractIds(updateRows, modelName, "update");
            this.assertRowsExist(modelName, ids, "update");
            boolean updated = modelService.updateList(modelName, updateRows);
            Assert.isTrue(updated, "Failed to update metadata of model {0}. ids={1}", modelName, ids);
        }
    }

    /**
     * Delete metadata by unique id
     *
     * @param modelName The name of the model
     * @param ids The list of codes for the data to be deleted
     */
    private void deleteByIds(String modelName, List<? extends Serializable> ids) {
        if (!CollectionUtils.isEmpty(ids)) {
            this.validateBatchSize(ids.size());
            List<Serializable> requestedIds = new ArrayList<>(ids);
            this.assertRowsExist(modelName, requestedIds, "delete");
            boolean deleted = modelService.deleteByIds(modelName, Cast.of(ids));
            Assert.isTrue(deleted, "Failed to delete metadata of model {0}. ids={1}", modelName, requestedIds);
        }
    }

    private void validateInsertIdEnabled() {
        Assert.notNull(SystemConfig.env, "System configuration has not been initialized.");
        Assert.isTrue(SystemConfig.env.isEnableInsertId(),
                "system.enableInsertId must be enabled when upgrading runtime metadata.");
    }

    private List<Serializable> extractIds(List<Map<String, Object>> rows, String modelName, String operation) {
        List<Serializable> ids = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object id = row.get(ModelConstant.ID);
            Assert.notNull(id, "When {0} metadata of model {1}, each row must contain id. {2}",
                    operation, modelName, row);
            ids.add((Serializable) id);
        }
        return ids;
    }

    private void assertRowsExist(String modelName, List<? extends Serializable> ids, String operation) {
        Set<Serializable> distinctIds = new LinkedHashSet<>(ids);
        FlexQuery flexQuery = new FlexQuery(List.of(ModelConstant.ID), new Filters().in(ModelConstant.ID, distinctIds));
        List<Map<String, Object>> existingRows = modelService.searchList(modelName, flexQuery);
        Set<Serializable> existingIds = existingRows.stream()
                .map(row -> (Serializable) row.get(ModelConstant.ID))
                .collect(Collectors.toSet());
        List<Serializable> missingIds = distinctIds.stream()
                .filter(id -> !existingIds.contains(id))
                .toList();
        Assert.isTrue(missingIds.isEmpty(), "Cannot {0} metadata of model {1}. Missing ids: {2}",
                operation, modelName, missingIds);
    }

    /**
     * Upgrades the metadata of multiple models, all within a single transaction
     * to avoid refreshing the model pool repeatedly and missing dependency data.
     *
     * @param metadataPackages the metadata packages to upgrade
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void upgradeMetadata(List<MetadataUpgradePackage> metadataPackages) {
        metadataPackages.forEach(modelPackage -> {
            String modelName = modelPackage.getModelName();
            this.validateRuntimeModel(modelName);
            // create
            this.createMetadata(modelName, modelPackage.getCreateRows());
            // update
            this.updateById(modelName, modelPackage.getUpdateRows());
            // delete
            this.deleteByIds(modelName, modelPackage.getDeleteIds());
        });
    }

    /**
     * Reload metadata.
     * The current replica will be unavailable if an exception occurs during the reload,
     * and the metadata needs to be fixed and reloaded.
     */
    @Override
    public void reloadMetadata() {
        // Send an inner broadcast to reload the metadata of replica containers.
        InnerBroadcastMessage message = new InnerBroadcastMessage();
        message.setBroadcastType(InnerBroadcastType.RELOAD_METADATA);
        message.setContext(ContextHolder.cloneContext());
        innerBroadcastProducer.sendInnerBroadcast(message);
    }

    /**
     * Export runtime metadata rows for the given version-controlled model, scoped to an app.
     * <p>
     * Main entities carry {@code appId} directly — filter on that. Translation entities
     * (suffix {@code Trans}) do not; the parent model does, so a two-step query resolves
     * the parent row ids for this app and filters the Trans rows by {@code rowId}.
     */
    @Override
    public List<Map<String, Object>> exportRuntimeMetadata(String modelName, Long appId) {
        Assert.notBlank(modelName, "Model name cannot be empty.");
        Assert.notNull(appId, "App id cannot be null.");
        Filters filters = buildAppScopedFilter(modelName, appId);
        if (filters == null) {
            return List.of();
        }
        FlexQuery flexQuery = new FlexQuery(ModelManager.getModelFieldsWithoutXToMany(modelName), filters);
        return modelService.searchList(modelName, flexQuery);
    }

    /**
     * Build an appId-scoped {@link Filters} for the runtime model.
     * <p>
     * Returns {@code null} when the scope resolves to "no possible rows" (e.g. a Trans
     * model whose parent has no rows for this app) so the caller can short-circuit
     * instead of issuing a query that the filter layer would reject for being empty.
     */
    private Filters buildAppScopedFilter(String modelName, Long appId) {
        if (!modelName.endsWith(ModelConstant.MODEL_TRANS_SUFFIX)) {
            Assert.isTrue(ModelManager.existField(modelName, "appId"),
                    "Runtime model {0} has no appId column and is not a translation model; cannot scope by app.",
                    modelName);
            return new Filters().eq("appId", appId);
        } else {
            String businessModel = modelName.substring(0, modelName.length() - ModelConstant.MODEL_TRANS_SUFFIX.length());
            Assert.isTrue(ModelManager.existField(businessModel, "appId"),
                    "Business model {0} must carry appId to scope its translations.", businessModel);
            FlexQuery flexQuery = new FlexQuery(List.of(ModelConstant.ID), new Filters().eq("appId", appId));
            List<Map<String, Object>> rows = modelService.searchList(businessModel, flexQuery);
            List<Serializable> businessIds = rows.stream()
                    .map(row -> (Serializable) row.get(ModelConstant.ID))
                    .toList();
            if (businessIds.isEmpty()) {
                return null;
            }
            return new Filters().in("rowId", businessIds);
        }
    }
}
