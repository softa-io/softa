package io.softa.starter.metadata.service.impl;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
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
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.CascadeFieldWalker;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.metadata.constant.MetadataConstant;
import io.softa.starter.metadata.controller.dto.MetaModelDTO;
import io.softa.starter.metadata.controller.dto.PathResolution;
import io.softa.starter.metadata.controller.dto.ResolveCascadedPathsResponse;
import io.softa.starter.metadata.dto.MetadataUpgradePackage;
import io.softa.starter.metadata.message.InnerBroadcastProducer;
import io.softa.starter.metadata.message.dto.InnerBroadcastMessage;
import io.softa.starter.metadata.message.enums.InnerBroadcastType;
import io.softa.starter.metadata.service.MetadataService;

/**
 * Metadata upgrade service implementation.
 */
@Slf4j
@Service
public class MetadataServiceImpl implements MetadataService {

    @Autowired
    private ModelService<Serializable> modelService;

    @Autowired
    private InnerBroadcastProducer innerBroadcastProducer;

    @Autowired
    private PermissionService permissionService;

    /**
     * Get the MetaModelDTO object by modelName
     *
     * @param modelName model name
     * @return metaModelDTO object
     */
    @Override
    public MetaModelDTO getMetaModelDTO(String modelName) {
        return MetadataDtoMapper.toModelDTO(modelName);
    }

    /**
     * Resolve cascaded field paths from {@code rootModel} in one round-trip.
     * <p>
     * Failure isolation: each path walks independently via {@link CascadeFieldWalker}
     * with a local closure / access-fields collector; the local state is merged into
     * the request-wide state only when the walk succeeds, so a failed path can never
     * pollute the {@code metaModels} closure or the permission-check input.
     * <p>
     * Permission is enforced at request level: any forbidden model/field on the
     * union of successful paths raises a {@code PermissionException} (HTTP 403).
     */
    @Override
    public ResolveCascadedPathsResponse resolveCascadedPaths(String rootModel, List<String> paths) {
        Assert.notBlank(rootModel, "rootModel cannot be empty.");
        Assert.notEmpty(paths, "paths cannot be empty.");
        ModelManager.validateModel(rootModel);

        // Closure holds only the related models reachable from successful paths;
        // the root is excluded because the caller necessarily already has it
        // (the page is rendering against rootModel).
        LinkedHashSet<String> closure = new LinkedHashSet<>();
        Map<String, Set<String>> accessFields = new HashMap<>();
        List<PathResolution> resolutions = new ArrayList<>(paths.size());

        for (String path : paths) {
            LinkedHashSet<String> localClosure = new LinkedHashSet<>();
            Map<String, Set<String>> localAccess = new HashMap<>();
            CascadeFieldWalker.Visitor collector = new CascadeFieldWalker.Visitor() {
                @Override
                public void onSegment(int index, String currentModel, MetaField field) {
                    localAccess.computeIfAbsent(currentModel, k -> new HashSet<>())
                            .add(field.getFieldName());
                }
                @Override
                public void onAdvance(int index, MetaField field, String nextModel) {
                    localClosure.add(nextModel);
                }
            };

            CascadeFieldWalker.Result result = CascadeFieldWalker.walk(rootModel, path, collector);
            if (result instanceof CascadeFieldWalker.Result.Ok(MetaField leaf)) {
                closure.addAll(localClosure);
                localAccess.forEach((model, fields) ->
                        accessFields.computeIfAbsent(model, k -> new HashSet<>()).addAll(fields));
                resolutions.add(PathResolution.success(path, MetadataDtoMapper.toFieldDTO(leaf)));
            } else {
                CascadeFieldWalker.Result.Failure f = (CascadeFieldWalker.Result.Failure) result;
                resolutions.add(PathResolution.failure(path, f.kind(), f.errorAt(), f.message()));
            }
        }

        // Request-level permission check on the union of successful paths.
        permissionService.checkModelCascadeFieldsAccess(rootModel, accessFields, AccessType.READ);

        List<MetaModelDTO> metaModels = closure.stream()
                .map(MetadataDtoMapper::toModelDTO)
                .collect(Collectors.toList());
        return new ResolveCascadedPathsResponse(metaModels, resolutions);
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
            modelService.createList(modelName, createRows);
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
            List<Long> ids = this.extractIds(updateRows, modelName, AccessType.UPDATE);
            this.lowMissedRowIds(modelName, ids, AccessType.UPDATE);
            modelService.updateList(modelName, updateRows);
        }
    }

    /**
     * Delete metadata by unique id
     *
     * @param modelName The name of the model
     * @param ids The list of codes for the data to be deleted
     */
    private void deleteByIds(String modelName, List<Long> ids) {
        if (!CollectionUtils.isEmpty(ids)) {
            this.validateBatchSize(ids.size());
            List<Long> requestedIds = new ArrayList<>(ids);
            this.lowMissedRowIds(modelName, requestedIds, AccessType.DELETE);
            modelService.deleteByIds(modelName, Cast.of(ids));
        }
    }

    private void validateInsertIdEnabled() {
        Assert.notNull(SystemConfig.env, "System configuration has not been initialized.");
        Assert.isTrue(SystemConfig.env.isEnableInsertId(),
                "system.enableInsertId must be enabled when upgrading runtime metadata.");
    }

    private List<Long> extractIds(List<Map<String, Object>> rows, String modelName, AccessType operation) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object id = row.get(ModelConstant.ID);
            Assert.notNull(id, "When {0} metadata of model {1}, each row must contain id. {2}",
                    operation, modelName, row);
            ids.add(IdUtils.convertIdToLong(id));
        }
        return ids;
    }

    private void lowMissedRowIds(String modelName, List<Long> ids, AccessType operation) {
        Set<Long> distinctIds = new LinkedHashSet<>(ids);
        FlexQuery flexQuery = new FlexQuery(List.of(ModelConstant.ID), new Filters().in(ModelConstant.ID, distinctIds));
        List<Map<String, Object>> existingRows = modelService.searchList(modelName, flexQuery);
        Set<Long> existingIds = existingRows.stream()
                .map(row -> (Long) row.get(ModelConstant.ID))
                .collect(Collectors.toSet());
        List<Long> missingIds = distinctIds.stream()
                .filter(id -> !existingIds.contains(id))
                .toList();
        if (!missingIds.isEmpty()) {
            log.error("Missing ids {} for operation {}", missingIds, operation);
        }
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
