package io.softa.starter.metadata.service.impl;

import java.io.Serializable;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.Cast;
import io.softa.framework.orm.domain.FileObject;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.orm.utils.FileUtils;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.metadata.entity.SysPreData;
import io.softa.starter.metadata.service.SysPreDataService;

import static io.softa.framework.orm.constant.ModelConstant.ID;

/**
 * SysPreData Model Service Implementation
 * Predefined data: model + preId as a unique identifier within a loading scope (system, or one
 * tenant), used to bind model row ID. ManyToOne and OneToOne fields directly reference preId,
 * ManyToMany fields reference a list of preIds, OneToMany fields support a data list, where the
 * data in the list does not need to declare the main model's preId but must declare the
 * relatedModel's preId.
 * <p>
 * Scopes never mix: a load writes, looks up, and resolves references strictly within its own
 * scope. Tenant seeds never reference shared data — cross-scope references are rejected
 * ({@link #validateReferenceScope}), as are seeds targeting models of the other scope's tenancy
 * ({@link #validateSeedScope}).
 * <p>
 * File-format concerns (JSON / CSV / XML) are delegated to {@link PreDataFormatParser}; this service owns the
 * predefined-data domain logic only — preId binding, main/sub-model ordering, and create-or-update reconciliation.
 */
@Service
public class SysPreDataServiceImpl extends EntityServiceImpl<SysPreData, Long> implements SysPreDataService {

    private final ModelService<Serializable> modelService;
    private final PreDataFormatParser formatParser = new PreDataFormatParser();

    public SysPreDataServiceImpl(ModelService<Serializable> modelService) {
        this.modelService = modelService;
    }

    /**
     * Load the specified list of predefined data files from the root directory: resources/data.
     * Supports data files in JSON, XML, and CSV formats. Data files support a two-layer domain model,
     * i.e., main model and subModel, but they will be created separately when loading.
     * The main model is created first to generate the main model id, then the subModel data is created.
     *
     * @param fileNames List of relative directory data file names to load
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void loadPreSystemData(List<String> fileNames) {
        String dataDir = BaseConstant.PREDEFINED_DATA_SYSTEM_DIR;
        runAsSystemScope(() -> {
            for (String fileName : fileNames) {
                FileObject fileObject = FileUtils.getFileObjectByPath(dataDir, fileName);
                loadFileObject(fileObject);
            }
        });
    }

    /**
     * Load the specified list of predefined tenant data files from the root directory: resources/data-tenant.
     * Supports data files in JSON, XML, and CSV formats. Data files support a two-layer domain model,
     * i.e., main model and subModel, but they will be created separately when loading.
     * The main model is created first to generate the main model id, then the subModel data is created.
     *
     * @param fileNames List of relative directory tenant data file names to load
     * @param tenantId tenant id to which the data will be loaded
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void loadPreTenantData(List<String> fileNames, Long tenantId) {
        if (SystemConfig.env.isEnableMultiTenancy()) {
            Assert.notNull(tenantId,
                    "Loading tenant predefined data requires a tenant id when multi-tenancy is enabled!");
        }
        String dataDir = BaseConstant.PREDEFINED_DATA_TENANT_DIR;
        Context tenantContext = ContextHolder.cloneContext();
        tenantContext.setTenantId(tenantId);
        ContextHolder.runWith(tenantContext, () -> {
            for (String fileName : fileNames) {
                FileObject fileObject = FileUtils.getFileObjectByPath(dataDir, fileName);
                loadFileObject(fileObject);
            }
        });
    }

    /**
     * Loads predefined data from a given multipart file.
     * This method processes the provided multipart file to load predefined data into the system.
     * The file is expected to be in a format recognized by the implementation, such as CSV, JSON, or XML.
     *
     * @param file the multipart file containing the predefined data to be loaded into the system.
     *             The file should not be null and must contain valid data as per the required format.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void loadPreSystemData(MultipartFile file) {
        FileObject fileObject = FileUtils.getFileObject(file);
        runAsSystemScope(() -> loadFileObject(fileObject));
    }

    /**
     * Run a load at system scope: bindings and seeded rows take tenantId = null regardless
     * of the caller's ambient tenant, so a tenant-context caller cannot tenant-stamp system seeds.
     */
    private void runAsSystemScope(Runnable task) {
        Context systemContext = ContextHolder.cloneContext();
        systemContext.setTenantId(null);
        ContextHolder.runWith(systemContext, task);
    }

    /**
     * Parse a file into its {@code modelName -> data} entries (format handling lives in
     * {@link PreDataFormatParser}) and load each model's predefined data in declaration order.
     *
     * @param fileObject fileObject with the file content
     */
    private void loadFileObject(FileObject fileObject) {
        formatParser.parse(fileObject).forEach(this::processModelData);
    }

    /**
     * Process the model predefined data, which can be a single Map or a List<Map> format.
     *
     * @param model Model name
     * @param predefinedData Predefined data
     */
    private void processModelData(String model, Object predefinedData) {
        ModelManager.validateModel(model);
        if (predefinedData instanceof List<?> listData) {
            listData.forEach(row -> {
                if (row instanceof Map<?, ?> rowMap) {
                    handlePredefinedData(model, Cast.of(rowMap));
                } else {
                    throw new IllegalArgumentException("When defining model data in List structure, " +
                            "the internal data only supports Map format {0}: {1}", model, predefinedData);
                }
            });
        } else if (predefinedData instanceof Map<?, ?> mapData) {
            handlePredefinedData(model, Cast.of(mapData));
        } else {
            throw new IllegalArgumentException(
                    "Model predefined data only supports Map or List<Map> format {0}: {1}", model, predefinedData);
        }
    }

    /**
     * Load a predefined data record.
     * If there is predefined data for OneToMany fields, recursively load the sub-table data after the
     * main row exists (so the generated main id can back-reference into it). The input {@code row} is
     * treated as read-only — it is split into a main-model map and a OneToMany map.
     * When the OneToMany field value is empty, it indicates the deletion of existing associated model data.
     *
     * @param model Model name
     * @param row Predefined data record
     */
    private Serializable handlePredefinedData(String model, Map<String, Object> row) {
        validateSeedScope(model);
        Map<String, Object> mainRow = new LinkedHashMap<>();
        Map<String, Object> oneToManyMap = new LinkedHashMap<>();
        // Separate OneToMany sub-data from the main-model fields; an ordered map keeps the
        // processing order consistent with the file definition.
        row.forEach((field, value) -> {
            if (FieldType.ONE_TO_MANY.equals(ModelManager.getModelField(model, field).getFieldType())) {
                oneToManyMap.put(field, value);
            } else {
                mainRow.put(field, value);
            }
        });
        // Load main model data first, then the OneToMany rows it owns.
        Serializable rowId = createOrUpdateData(model, mainRow);
        loadOneToManyRows(model, rowId, oneToManyMap);
        return rowId;
    }

    /**
     * A seed row must match the scope it is loaded under when multi-tenancy is enabled:
     * a system-scope load writing a multi-tenant model would stamp rows with tenantId = null
     * that no tenant can read, and a tenant-scope load writing a shared model would duplicate
     * the shared rows once per loading tenant. Both directions fail fast; the surrounding
     * transaction rolls the whole file back. Checked here so OneToMany sub-model recursion
     * is covered, not just top-level entries.
     *
     * @param model Model name being seeded
     */
    private void validateSeedScope(String model) {
        if (!SystemConfig.env.isEnableMultiTenancy()) {
            return;
        }
        boolean tenantScope = ContextHolder.getContext().getTenantId() != null;
        boolean tenantModel = ModelManager.getModel(model).isMultiTenant();
        Assert.notTrue(!tenantScope && tenantModel,
                "Model {0} is multi-tenant: load its predefined data per tenant via loadPreTenantData, " +
                "not as system data.", model);
        Assert.notTrue(tenantScope && !tenantModel,
                "Model {0} is a shared model: load its predefined data via loadPreSystemData, " +
                "not as tenant data.", model);
    }

    /**
     * Load OneToMany field data
     * Based on and retain the existing Many side ids, delete Many side data that does not exist in the predefined data file.
     *
     * @param model Main model name
     * @param mainId Main model row ID
     * @param oneToManyMap OneToMany { fieldName: data list} mapping relationship, the value must be a list type.
     */
    private void loadOneToManyRows(String model, Serializable mainId, Map<String, Object> oneToManyMap) {
        oneToManyMap.forEach((field, value) -> {
            Assert.isTrue(value instanceof Collection,
                    "The data of OneToMany field {0}:{1} must be a list: {2}", model, field, value);
            MetaField relation = ModelManager.getModelField(model, field);
            List<Serializable> manyIds = new ArrayList<>();
            for (Object item : (Collection<?>) value) {
                Assert.isTrue(item instanceof Map,
                        "The single predefined data of the OneToMany field {0}:{1} must be in Map format: {2}",
                        model, field, item);
                // Copy the child row and inject the back-reference to the main row, leaving the parsed input untouched.
                Map<String, Object> childRow = new LinkedHashMap<>(Cast.<Map<String, Object>>of(item));
                childRow.put(relation.getRelatedField(), mainId);
                manyIds.add(handlePredefinedData(relation.getRelatedModel(), childRow));
            }
            // Delete Many side data but retain those that appear in the predefined data file.
            Filters deleteFilters = new Filters().eq(relation.getRelatedField(), mainId);
            if (!manyIds.isEmpty()) {
                deleteFilters.notIn(ID, manyIds);
            }
            modelService.deleteByFilters(relation.getRelatedModel(), deleteFilters);
        });
    }

    /**
     * Determine whether to create or update predefined data based on whether the main model preId already exists.
     *
     * @param model Model name
     * @param row Predefined data record (main-model fields only)
     * @return Record ID created or updated
     */
    private Serializable createOrUpdateData(String model, Map<String, Object> row) {
        Optional<SysPreData> optionalPreData = getPreDataByPreId(model, row);
        if (optionalPreData.isPresent() && Boolean.TRUE.equals(optionalPreData.get().getFrozen())) {
            // The current data is frozen, and the data ID is returned directly
            return IdUtils.formatId(model, optionalPreData.get().getRowId());
        }
        // Resolve the preIds of ManyToOne, OneToOne, and ManyToMany fields to row IDs (returns a new
        // map; the caller's row is left untouched).
        Map<String, Object> resolved = resolveReferencedPreIds(model, row);
        if (optionalPreData.isEmpty()) {
            // The seed's `id` is the preId (tracking key). For an EXTERNAL_ID model it is ALSO the
            // row's primary key (code-as-id), so it must stay in the row — IdProcessor
            // requires a non-empty id for EXTERNAL_ID. For generated-id strategies it is tracking-only
            // and removed so the strategy assigns the surrogate id.
            String preId = ModelManager.getIdStrategy(model) == IdStrategy.EXTERNAL_ID
                    ? (String) resolved.get(ID)
                    : (String) resolved.remove(ID);
            Serializable rowId = modelService.createOne(model, resolved);
            generatePreData(model, preId, rowId);
            return rowId;
        } else {
            SysPreData preData = optionalPreData.get();
            // Update the data and return the data ID
            Serializable rowId = IdUtils.formatId(model, preData.getRowId());
            resolved.put(ID, rowId);
            // Clear other fields that do not appear in the predefined data
            Set<String> updatableStoredFields = ModelManager.getModelUpdatableFieldsWithoutXToMany(model);
            updatableStoredFields.removeAll(resolved.keySet());
            updatableStoredFields.forEach(fieldName -> resolved.put(fieldName, null));
            boolean result = modelService.updateOne(model, resolved);
            if (!result) {
                boolean isExist = modelService.exist(model, rowId);
                Assert.isTrue(isExist, "Updating predefined data for model {0} ({1}) failed " +
                        "as it has already been physically deleted!", model, preData.getRowId());
            }
            return preData.getRowId();
        }
    }

    /**
     * Get the SysPreData object by preID.
     * Each tenant owns its own binding for a preId, so re-loading the same file under another
     * tenant creates that tenant's rows instead of touching the first tenant's.
     *
     * @param model Model name
     * @param row Predefined data record
     * @return SysPreData object
     */
    private Optional<SysPreData> getPreDataByPreId(String model, Map<String, Object> row) {
        Assert.isTrue(row.containsKey(ID), "Predefined data for model {0} must include the preID: {1}", model, row);
        Object preId = row.get(ID);
        Assert.isTrue(preId instanceof String, "Model {0} predefined data's preId must be of type String: {1}", model, preId);
        Long tenantId = ContextHolder.getContext().getTenantId();
        return getScopedBindings(model, List.of((String) preId), tenantId).stream().findFirst();
    }

    /**
     * Query the bindings of the given preIds within ONE scope: tenantId = T selects a tenant's
     * bindings, null selects the system bindings (tenant_id IS NULL). The single query primitive
     * behind the idempotency lookup and the reference resolution — every binding access is
     * scope-exact.
     *
     * @param model Model name
     * @param preIds Predefined IDs
     * @param tenantId tenant id of the scope, null for the system scope
     * @return bindings found in this scope
     */
    private List<SysPreData> getScopedBindings(String model, List<String> preIds, Long tenantId) {
        Filters filters = new Filters().eq(SysPreData::getModel, model).in(SysPreData::getPreId, preIds);
        if (tenantId == null) {
            filters.isNotSet(SysPreData::getTenantId);
        } else {
            filters.eq(SysPreData::getTenantId, tenantId);
        }
        return this.searchList(filters);
    }

    /**
     * Resolve the preIds of ManyToOne, OneToOne, and ManyToMany fields to the bound row IDs, returning a
     * NEW row map — the input {@code row} is left unmodified, so the caller owns the resolved copy.
     *
     * @param model Model name
     * @param row Predefined data record
     * @return a copy of {@code row} with reference preIds replaced by row IDs
     */
    private Map<String, Object> resolveReferencedPreIds(String model, Map<String, Object> row) {
        Map<String, Object> resolved = new LinkedHashMap<>(row);
        for (Map.Entry<String, Object> entry : resolved.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            MetaField metaField = ModelManager.getModelField(model, entry.getKey());
            if (FieldType.TO_ONE_TYPES.contains(metaField.getFieldType())) {
                validateReferenceScope(model, metaField);
                if (!(entry.getValue() instanceof Long || entry.getValue() instanceof Integer)) {
                    Assert.isTrue(entry.getValue() instanceof String,
                            "Model {0} field {1}:{2} preID must be of type String: {3}",
                            model, entry.getKey(), metaField.getFieldType().getType(), entry.getValue());
                    Serializable rowId = this.getOriginalRowIdByPreId(metaField.getRelatedModel(), Cast.of(entry.getValue()));
                    entry.setValue(rowId);
                }
            } else if (FieldType.MANY_TO_MANY.equals(metaField.getFieldType())) {
                validateReferenceScope(model, metaField);
                Assert.isTrue(entry.getValue() instanceof Collection,
                        "Model {0} predefined data's {1} ManyToMany field value must be a list or empty",
                        model, entry.getKey());
                if (!CollectionUtils.isEmpty((Collection<?>) entry.getValue())) {
                    List<String> preIds = Cast.of(entry.getValue());
                    List<Serializable> rowIds = this.getOriginalRowIdsByPreIds(metaField.getRelatedModel(), preIds);
                    entry.setValue(rowIds);
                }
            }
        }
        return resolved;
    }

    /**
     * Seed references never cross scopes — the referenced-side mirror of {@link #validateSeedScope}:
     * when multi-tenancy is enabled, a tenant seed may only reference multi-tenant models (its own
     * tenant's rows) and a system seed only shared models. Checked on the reference field itself
     * (covering preId and raw-id values alike), so a violation names the rule instead of failing
     * later with a misleading "preID does not exist".
     *
     * @param model Model name being seeded
     * @param reference the TO_ONE / MANY_TO_MANY field carrying the reference
     */
    private void validateReferenceScope(String model, MetaField reference) {
        if (!SystemConfig.env.isEnableMultiTenancy()) {
            return;
        }
        boolean tenantScope = ContextHolder.getContext().getTenantId() != null;
        boolean tenantModel = ModelManager.getModel(reference.getRelatedModel()).isMultiTenant();
        Assert.isTrue(tenantScope == tenantModel,
                "Seed data of model {0} references {1} via field {2}, which crosses the loading scope: " +
                "tenant seeds may only reference multi-tenant models, and system seeds only shared models.",
                model, reference.getRelatedModel(), reference.getFieldName());
    }

    /**
     * Get the model row ID bound by preId.
     * @param model Model name
     * @param preId Predefined ID
     * @return Model row ID
     */
    private Serializable getOriginalRowIdByPreId(String model, String preId) {
        return getOriginalRowIdsByPreIds(model, List.of(preId)).getFirst();
    }

    /**
     * Get the model row IDs bound by preIds, in the order of the input preIds. Resolution is
     * scope-exact like every binding lookup — references never cross scopes. Every preId must
     * resolve; the missing ones are reported together.
     *
     * @param model Model name
     * @param preIds Predefined IDs
     * @return List of model row IDs
     */
    private List<Serializable> getOriginalRowIdsByPreIds(String model, List<String> preIds) {
        Long tenantId = ContextHolder.getContext().getTenantId();
        Map<String, Serializable> resolved = new HashMap<>();
        getScopedBindings(model, preIds, tenantId).forEach(binding ->
                resolved.putIfAbsent(binding.getPreId(), IdUtils.formatId(model, binding.getRowId())));
        List<String> missing = preIds.stream().filter(preId -> !resolved.containsKey(preId)).toList();
        Assert.isTrue(missing.isEmpty(), "The preIDs of the predefined data for model {0}: {1} do not exist " +
                "in the predefined data table and may not have been created yet!", model, missing);
        return preIds.stream().map(resolved::get).toList();
    }

    /**
     * Create predefined data and bind the model row ID.
     *
     * @param model Model name
     * @param preId Predefined ID
     * @param rowId Model record ID
     */
    private void generatePreData(String model, String preId, Serializable rowId) {
        SysPreData preData = new SysPreData();
        preData.setModel(model);
        preData.setPreId(preId);
        preData.setRowId(rowId.toString());
        // Scope stamp from the same context tenant that fillTenantFieldForInsert stamped on the
        // seeded rows themselves: null = system scope, non-null = that tenant's scope.
        preData.setTenantId(ContextHolder.getContext().getTenantId());
        this.createOne(preData);
    }
}
