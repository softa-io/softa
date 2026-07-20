package io.softa.framework.orm.meta;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.ListUtils;
import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.utils.GraphUtils;

/**
 * Model Manager, maintaining model metadata and field metadata in memory.
 */
@Component
public class ModelManager {

    private static volatile MetadataSnapshot snapshot = MetadataSnapshot.empty();
    private static final ThreadLocal<MetadataSnapshot> BUILDING_SNAPSHOT = new ThreadLocal<>();

    private final ReentrantLock initLock = new ReentrantLock();

    @Autowired
    private JdbcService<?> jdbcService;

    // indexMap is an independent registry keyed by the globally-unique index name — NOT mounted
    // under a model — so a violated index name resolves in one hop (see getIndex / the friendly
    // duplicate-key resolver).
    private record MetadataSnapshot(Map<String, MetaModel> modelMap, Map<String, Map<String, MetaField>> modelFields,
                                    Map<String, MetaIndex> indexMap) {
        private static MetadataSnapshot empty() {
            return new MetadataSnapshot(Map.of(), Map.of(), Map.of());
        }
    }

    private static MetadataSnapshot currentSnapshot() {
        MetadataSnapshot buildingSnapshot = BUILDING_SNAPSHOT.get();
        return buildingSnapshot != null ? buildingSnapshot : snapshot;
    }

    private static Map<String, MetaModel> modelMap() {
        return currentSnapshot().modelMap();
    }

    private static Map<String, Map<String, MetaField>> modelFields() {
        return currentSnapshot().modelFields();
    }

    private static Map<String, MetaIndex> indexMap() {
        return currentSnapshot().indexMap();
    }

    private static MetadataSnapshot createMutableSnapshot() {
        return new MetadataSnapshot(new HashMap<>(200), new HashMap<>(200), new HashMap<>(64));
    }

    private static MetadataSnapshot freezeSnapshot(MetadataSnapshot draft) {
        Map<String, MetaModel> frozenModelMap = Collections.unmodifiableMap(new HashMap<>(draft.modelMap()));
        Map<String, Map<String, MetaField>> frozenModelFields = draft.modelFields().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
        Map<String, MetaIndex> frozenIndexMap = Collections.unmodifiableMap(new HashMap<>(draft.indexMap()));
        return new MetadataSnapshot(frozenModelMap, frozenModelFields, frozenIndexMap);
    }

    /**
     * Initialize ModelManager, load MetaModel and MetaField data from the database
     */
    public void init() {
        initLock.lock();
        try {
            MetadataSnapshot draft = createMutableSnapshot();
            BUILDING_SNAPSHOT.set(draft);
            List<MetaModel> models = jdbcService.selectMetaEntityList("SysModel", MetaModel.class, null);
            List<MetaField> fields = jdbcService.selectMetaEntityList("SysField", MetaField.class, null);
            List<MetaIndex> indexes = jdbcService.selectMetaEntityList("SysModelIndex", MetaIndex.class, null);
            if (ListUtils.allNotNull(models, fields)) {
                this.initModels(models);
                // Initialize basic field information
                this.initBasicFields(fields);
                // Validate model attributes
                this.validateModelAttributes(models);
                // Validate field attributes
                this.validateFieldAttributes(fields);
                // Register indexes into the independent global registry, failing fast on a duplicate name.
                // Index metadata is secondary (backs the friendly duplicate-key resolver), so a null read
                // must not block model loading — treat it as no indexes.
                this.initIndexes(indexes == null ? List.of() : indexes);
                // Identify composition relationships and build childModels
                this.identifyChildModels();
                // Build the inbound TO_ONE reverse-reference index (delete strategy)
                this.identifyOnDeleteRefs();
                // Reject cyclic / self-referential CASCADE so the runtime cascade needs no cycle guard
                this.validateCascadeAcyclic();
                // Identify the audit fields of the models.
                this.identifyAuditFields(models);
                // Seal the model attributes to make them immutable after initialization
                this.sealModelAttributes(models);
                snapshot = freezeSnapshot(draft);
            }
        } finally {
            BUILDING_SNAPSHOT.remove();
            initLock.unlock();
        }
    }

    /**
     * Initialize model data: MODEL_MAP, MODEL_FIELDS
     *
     * @param models model metadata
     */
    private void initModels(List<MetaModel> models) {
        models.forEach(model -> {
            modelMap().put(model.getModelName(), model);
            modelFields().put(model.getModelName(), new HashMap<>(4));
        });
    }

    /**
     * Register every index into the independent global registry (index name &rarr; index).
     * Index names must be globally unique — PostgreSQL namespaces index / constraint names per
     * schema, and the friendly duplicate-key resolver keys on the name alone — so a duplicate
     * fails fast at load, naming both owning models.
     *
     * @param indexes index metadata
     */
    private void initIndexes(List<MetaIndex> indexes) {
        assertIndexNamesGloballyUnique(indexes);
        indexes.forEach(index -> indexMap().put(index.getIndexName(), index));
    }

    /**
     * Reject a duplicate index name across models. PostgreSQL namespaces index / constraint names
     * per schema, and the friendly duplicate-key resolver keys on the name alone, so index names
     * must be globally unique; a duplicate fails fast, naming both owning models. Package-visible
     * so the uniqueness validation is unit-testable (mirrors {@link #assertCascadeAcyclic}).
     *
     * @param indexes index metadata
     */
    static void assertIndexNamesGloballyUnique(List<MetaIndex> indexes) {
        Map<String, String> nameToModel = new HashMap<>(indexes.size());
        for (MetaIndex index : indexes) {
            String previousModel = nameToModel.putIfAbsent(index.getIndexName(), index.getModelName());
            Assert.isTrue(previousModel == null,
                    "Duplicate index name ''{0}'' declared on both model ''{1}'' and ''{2}'' — index names "
                            + "must be globally unique; rename one (or drop the explicit indexName to auto-derive).",
                    index.getIndexName(), previousModel, index.getModelName());
        }
    }

    /**
     * Initialize basic field information: MODEL_FIELDS
     * @param fields field metadata
     */
    private void initBasicFields(List<MetaField> fields) {
        fields.forEach(field -> {
            Assert.notNull(field.getFieldType(), "The fieldType of field metadata is not supported: {0}", field);
            Assert.isTrue(modelMap().containsKey(field.getModelName()),
                    "Model for field does not exist in model metadata: {0}", field);
            // Convert the string default value to the default value object
            field.setDefaultValueObject(FieldType.convertStringToFieldValue(field.getFieldType(), field.getDefaultValue()));
            modelFields().get(field.getModelName()).put(field.getFieldName(), field);
        });
    }

    /**
     * Validate the model attributes after loading basic fields info.
     *
     * @param metaModels model metadata list
     */
    public void validateModelAttributes(List<MetaModel> metaModels) {
        for (MetaModel metaModel : metaModels) {
            // Check if the model name is valid, to avoid SQL injection.
            Assert.isTrue(StringTools.isModelName(metaModel.getModelName()),
                    "Model name `{0}` does not meet the specification!", metaModel.getModelName());
            Assert.isTrue(StringTools.isTableOrColumn(metaModel.getTableName()),
                "The table name `{0}` for model `{1}` does not meet the specification!",
                metaModel.getTableName(), metaModel.getModelName());
            // Check if the soft delete model has a `deleted` field or specified flag
            validateSoftDeleted(metaModel);
            // Check if the active control model has a `active` field
            validateActiveControl(metaModel);
            // Check and complete the model-level displayName configuration.
            validateModelDisplayName(metaModel);
            validateDefaultOrder(metaModel);
            // Check and complete the searchName configuration
            validateSearchName(metaModel);
            // Check if the timeline model contains the required timeline fields: sliceId, effectiveStartDate, effectiveEndDate
            validateTimelineFields(metaModel.getModelName());
            // Check if the multi-tenant model contains the tenantId field
            validateMultiTenant(metaModel);
            // Check if the optimistic lock control model contains the version field
            validateVersionField(metaModel);
            // Check if the model dataSource attribute is valid
            validateModelDataSource(metaModel);
            // Check if the model businessKey attribute is valid
            validateBusinessKey(metaModel);
        }
    }

    /**
     * Validate the field attributes after loading basic fields info.
     *
     * @param metaFields field metadata list
     */
    public void validateFieldAttributes(List<MetaField> metaFields) {
        for (MetaField metaField : metaFields) {
            // Check if the field name is valid, to avoid SQL injection.
            Assert.isTrue(StringTools.isFieldName(metaField.getFieldName()),
                    "{0}:{1}, the fieldName is invalid!",
                    metaField.getModelName(), metaField.getFieldName());
            Assert.isTrue(StringTools.isTableOrColumn(metaField.getColumnName()),
                    "{0}:{1}, the columnName {2} is invalid!",
                    metaField.getModelName(), metaField.getFieldName(), metaField.getColumnName());
            Assert.notTrue(ModelConstant.VIRTUAL_FIELDS.contains(metaField.getFieldName()),
                    "Model field {0}:{1} cannot use a virtual field name!",
                    metaField.getModelName(), metaField.getFieldName());
            // Check if the related field is valid
            if (FieldType.RELATED_TYPES.contains(metaField.getFieldType())) {
                validateRelationalField(metaField);
            }
            // onDelete is meaningful only on TO_ONE relations (reject elsewhere — determinism).
            Assert.isTrue(metaField.getOnDelete() == null
                            || FieldType.TO_ONE_TYPES.contains(metaField.getFieldType()),
                    "{0}:{1}, onDelete is only valid on a MANY_TO_ONE / ONE_TO_ONE field!",
                    metaField.getModelName(), metaField.getFieldName());
            if (StringUtils.isNotBlank(metaField.getCascadedField())) {
                // Verify the cascaded field and update the MetaModel object
                verifyCascadedField(metaField);
            }
            if (metaField.isComputed()) {
                // Verify the computed field and update the MetaModel object
                verifyComputedField(metaField);
            }
            // Verify and update the `readonly` attribute of field
            verifyReadonlyAttribute(metaField);
            // Verify and update the `dynamic` attribute of field
            verifyDynamicAttribute(metaField);
        }
    }

    /**
     * Identify the audit fields of the models.
     *
     * @param metaModels model metadata list
     */
    private void identifyAuditFields(List<MetaModel> metaModels) {
        for (MetaModel metaModel : metaModels) {
            if (modelFields().get(metaModel.getModelName()).containsKey(ModelConstant.CREATED_ID)) {
                metaModel.addAuditCreateField(ModelConstant.CREATED_ID);
            }
            if (modelFields().get(metaModel.getModelName()).containsKey(ModelConstant.UPDATED_ID)) {
                metaModel.addAuditUpdateField(ModelConstant.UPDATED_ID);
            }
            if (modelFields().get(metaModel.getModelName()).containsKey(ModelConstant.CREATED_BY)) {
                metaModel.addAuditCreateField(ModelConstant.CREATED_BY);
            }
            if (modelFields().get(metaModel.getModelName()).containsKey(ModelConstant.UPDATED_BY)) {
                metaModel.addAuditUpdateField(ModelConstant.UPDATED_BY);
            }
            if (modelFields().get(metaModel.getModelName()).containsKey(ModelConstant.CREATED_TIME)) {
                metaModel.addAuditCreateField(ModelConstant.CREATED_TIME);
            }
            if (modelFields().get(metaModel.getModelName()).containsKey(ModelConstant.UPDATED_TIME)) {
                metaModel.addAuditUpdateField(ModelConstant.UPDATED_TIME);
            }
        }
    }

    /**
     * Identify composition relationships from OneToMany fields and populate childModels on each model.
     * A composition field (composition=true) means the child model's lifecycle is fully managed by the parent.
     */
    private void identifyChildModels() {
        for (Map.Entry<String, Map<String, MetaField>> entry : modelFields().entrySet()) {
            String modelName = entry.getKey();
            MetaModel metaModel = modelMap().get(modelName);
            for (MetaField field : entry.getValue().values()) {
                if (FieldType.ONE_TO_MANY.equals(field.getFieldType())) {
                    metaModel.addChildModel(field.getRelatedModel());
                }
            }
        }
    }

    /**
     * Build the onDelete reverse-reference index: for every TO_ONE field carrying a non-KEEP
     * {@code onDelete}, register it on the model it points AT, so deleting that model can enforce the
     * policy without scanning the whole field graph. KEEP ({@code onDelete == null}) fields are skipped
     * — the default costs nothing.
     */
    private void identifyOnDeleteRefs() {
        for (Map<String, MetaField> fields : modelFields().values()) {
            for (MetaField field : fields.values()) {
                if (field.getOnDelete() != null && FieldType.TO_ONE_TYPES.contains(field.getFieldType())) {
                    MetaModel target = modelMap().get(field.getRelatedModel());
                    if (target != null) {
                        target.addOnDeleteRefField(field);
                    }
                }
            }
        }
    }

    /**
     * Reject cyclic / self-referential {@code onDelete=CASCADE}. Only CASCADE recurses through
     * {@code deleteByIds}, so as long as the CASCADE graph — edge "deleting the referenced model cascades
     * to its referrer" — is acyclic, the recursion is bounded and the runtime needs <b>no</b> cycle guard.
     * A cycle (including a self-loop, e.g. a self-referential parent FK) would recurse forever, so it is
     * forbidden at boot; delete such hierarchies explicitly in application code. (Diamonds are not cycles
     * and stay allowed: a re-converged row is already gone, so the repeat delete no-ops.) The same pass
     * also rejects a CASCADE chain longer than {@link BaseConstant#MAX_CASCADE_DEPTH} models — bounding
     * the runtime recursion depth.
     */
    private void validateCascadeAcyclic() {
        Map<String, List<String>> cascadeGraph = new HashMap<>();
        for (Map.Entry<String, MetaModel> entry : modelMap().entrySet()) {
            for (MetaField field : entry.getValue().getOnDeleteRefFields()) {
                if (OnDelete.CASCADE == field.getOnDelete()) {
                    // deleting entry.getKey() (the One) cascades to field.getModelName() (the referrer / Many)
                    cascadeGraph.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(field.getModelName());
                }
            }
        }
        assertCascadeAcyclic(cascadeGraph);
    }

    /**
     * Reject any cycle (including a self-loop) and any CASCADE chain deeper than
     * {@link BaseConstant#MAX_CASCADE_DEPTH} in the CASCADE graph. Delegates the generic graph algorithms
     * to {@link GraphUtils} (cycle-find + longest-path) and phrases the CASCADE-specific fail-fast errors
     * here. Package-visible so the CASCADE validation is unit-testable.
     *
     * @param graph model → the models its deletion cascades to (CASCADE edges only)
     */
    static void assertCascadeAcyclic(Map<String, List<String>> graph) {
        List<String> cycle = GraphUtils.findCycle(graph);
        Assert.isTrue(cycle.isEmpty(),
                "onDelete=CASCADE forms a cycle [{0}] — cyclic / self-referential cascade delete is not "
                        + "supported; delete such hierarchies explicitly in application code.",
                String.join(" -> ", cycle));
        List<String> longest = GraphUtils.longestPath(graph);
        Assert.isTrue(longest.size() <= BaseConstant.MAX_CASCADE_DEPTH,
                "onDelete=CASCADE chain [{0}] is {1} model(s) deep, over the MAX_CASCADE_DEPTH limit ({2}) — "
                        + "shorten the cascade, or delete deep hierarchies explicitly in application code.",
                String.join(" -> ", longest), longest.size(), BaseConstant.MAX_CASCADE_DEPTH);
    }

    /**
     * Seal the model fields and related attributes to make them immutable after initialization,
     * preventing accidental modification.
     *
     * @param metaModels model metadata objects
     */
    private void sealModelAttributes(List<MetaModel> metaModels) {
        for (MetaModel metaModel : metaModels) {
            metaModel.sealModelFields();
        }
    }

    /**
     * Check if the soft delete model has a `deleted` field.
     * When the data of soft deleted model is deleted, set `deleted=true`.
     * The active data can be queried by filter `deleted=false`.
     *
     * @param metaModel model metadata object
     */
    private static void validateSoftDeleted(MetaModel metaModel) {
        if (metaModel.isSoftDelete()) {
            String softDeleteField;
            if (StringUtils.isBlank(metaModel.getSoftDeleteField())) {
                softDeleteField = ModelConstant.SOFT_DELETED_FIELD;
                metaModel.setSoftDeleteField(softDeleteField);
            } else {
                softDeleteField = metaModel.getSoftDeleteField();
            }
            MetaField deletedField = modelFields().get(metaModel.getModelName()).get(softDeleteField);
            Assert.notNull(deletedField, "`{0}` model enable soft delete, but not exist `{1}` field!",
                    metaModel.getModelName(), softDeleteField);
            deletedField.setDefaultValueObject(false);
        }
    }

    /**
     * Check if the active control model has an `active` field.
     * When the data of active control model is inactive, set `active=false`.
     * The inactive data can be queried by filter `active=false`.
     *
     * @param metaModel model metadata object
     */
    private static void validateActiveControl(MetaModel metaModel) {
        if (metaModel.isActiveControl()) {
            MetaField activeField = modelFields().get(metaModel.getModelName()).get(ModelConstant.ACTIVE_CONTROL_FIELD);
            Assert.notNull(activeField, "`{0}` model enable active control, but not exist `{1}` field!",
                    metaModel.getModelName(), ModelConstant.ACTIVE_CONTROL_FIELD);
            activeField.setDefaultValueObject(true);
        }
    }

    /**
     * Validate the model-level `displayName`, the display name fields are ordered as defined.
     * When the `displayName` is not defined, if there is a `name` field, it will be displayed as `displayName`.
     * If there is no `name` field, it will be displayed as `id`.
     *
     * @param metaModel model metadata object
     */
    private static void validateModelDisplayName(MetaModel metaModel) {
        List<String> displayName = metaModel.getDisplayName();
        if (!CollectionUtils.isEmpty(displayName)) {
            Assert.isTrue(!displayName.contains(ModelConstant.DISPLAY_NAME),
                    "Model {0} displayName cannot contain the 'displayName' keyword itself!", metaModel.getModelName());
            validateStoredFields(metaModel.getModelName(), displayName);
        } else if (existField(metaModel.getModelName(), "name")) {
            metaModel.setDisplayName(Collections.singletonList("name"));
        } else {
            metaModel.setDisplayName(Collections.singletonList(ModelConstant.ID));
        }
    }

    private static void validateDefaultOrder(MetaModel metaModel) {
        Orders defaultOrder = metaModel.getDefaultOrder();
        if (defaultOrder != null && !defaultOrder.isEmpty()) {
            for (List<String> order : defaultOrder.getOrderList()) {
                Assert.isTrue(order.size() == 2, "The defaultOrder {0} of model {1} is invalid! Only `fieldName ASC/DESC` format is allowed.",
                        order, metaModel.getModelName());
                String fieldName = order.get(0);
                String orderType = order.get(1);
                Assert.isTrue(Orders.ASC.equalsIgnoreCase(orderType) || Orders.DESC.equalsIgnoreCase(orderType),
                        "The defaultOrder {0} of model {1} is invalid! The order type must be ASC or DESC.",
                        order, metaModel.getModelName());
                Assert.isTrue(existField(metaModel.getModelName(), fieldName),
                        "The defaultOrder {0} of model {1} is invalid! The field `{2}` does not exist.",
                        order, metaModel.getModelName(), fieldName);
            }
        }
    }

    /**
     * Validate the searchName.
     * When the `searchName` is not defined, if there is a `name` field, it will be used as `searchName`.
     *
     * @param metaModel model metadata object
     */
    private static void validateSearchName(MetaModel metaModel) {
        List<String> searchName = metaModel.getSearchName();
        if (!CollectionUtils.isEmpty(searchName)) {
            Assert.isTrue(!searchName.contains(ModelConstant.SEARCH_NAME),
                    "The searchName of model {0} cannot contain the 'searchName' keyword itself!", metaModel.getModelName());
            searchName.forEach(field -> {
                FieldType fieldType = getModelField(metaModel.getModelName(), field).getFieldType();
                Assert.isTrue(FieldType.STRING.equals(fieldType),
                        "The `searchName` attribute only supports string type fields, not model {0} field {1} type {2}!",
                        metaModel.getModelName(), field, fieldType.getType());
            });
        } else if (existField(metaModel.getModelName(), "name")) {
            metaModel.setSearchName(Collections.singletonList("name"));
        } else {
            metaModel.setSearchName(Collections.singletonList(ModelConstant.ID));
        }
    }

    /**
     * Validate whether the timeline model contains the required timeline fields:
     *      sliceId, id, effectiveStartDate, effectiveEndDate.
     *
     * @param modelName model name
     */
    private static void validateTimelineFields(String modelName) {
        if (isTimelineModel(modelName)) {
            Set<String> currentModelFields = modelFields().get(modelName).keySet();
            Set<String> subFields = new HashSet<>(ModelConstant.TIMELINE_FIELDS);
            subFields.removeAll(currentModelFields);
            Assert.isTrue(subFields.isEmpty(), "Timeline model {0} must contain the required fields {1}!", modelName, subFields);
            // The auto-increment lands on the physical sliceId, so the shared logical `id`
            // column must be app-generated for first slices.
            Assert.notTrue(IdStrategy.DB_AUTO_ID.equals(getIdStrategy(modelName)),
                    "Timeline model {0} requires an app-generated logical id: use idStrategy = "
                            + "DISTRIBUTED_LONG / DISTRIBUTED_STRING / EXTERNAL_ID.", modelName);
        }
    }

    /**
     * Validate whether the multi-tenant model contains the `tenantId` field.
     *
     * @param metaModel model metadata object
     */
    private static void validateMultiTenant(MetaModel metaModel) {
        if (metaModel.isMultiTenant()) {
            Assert.isTrue(modelFields().get(metaModel.getModelName()).containsKey(ModelConstant.TENANT_ID),
                    "The multi-tenant model {0} must contain the `tenantId` field!", metaModel.getModelName());
        }
    }

    /**
     * Validate whether the optimistic lock control model contains the `version` field.
     *
     * @param metaModel model metadata object
     */
    private static void validateVersionField(MetaModel metaModel) {
        if (metaModel.isVersionLock()) {
            Assert.isTrue(modelFields().get(metaModel.getModelName()).containsKey(ModelConstant.VERSION),
                    "The model {0} must contain the `version` field when using optimistic lock control!",
                    metaModel.getModelName());
        }
    }

    /**
     * Validate the model dataSource attribute.
     * The system model cannot be configured with a dataSource.
     *
     * @param metaModel model metadata object
     */
    private static void validateModelDataSource(MetaModel metaModel) {
        String dataSource = metaModel.getDataSource();
        if (ModelConstant.SYSTEM_MODEL.contains(metaModel.getModelName()) && StringUtils.isNotBlank(dataSource)) {
            throw new IllegalArgumentException("The system model {0} cannot be configured with a dataSource {1}!",
                    metaModel.getModelName(), dataSource);
        }
    }

    /**
     * Check if the fields exists in the model
     *
     * @param metaModel model metadata object
     */
    private static void validateBusinessKey(MetaModel metaModel) {
        List<String> businessKey = metaModel.getBusinessKey();
        if (CollectionUtils.isEmpty(businessKey)) {
            return;
        }
        validateStoredFields(metaModel.getModelName(), businessKey);
        Set<String> businessKeySet = new HashSet<>(businessKey);
        Assert.isTrue(businessKeySet.size() == businessKey.size(),
                "The businessKey of model {0} contains duplicate fields, {1}.",
                metaModel.getModelName(), businessKey);
    }

    /**
     * Check if the relational field attributes are valid.
     *
     * @param metaField field metadata object
     */
    private static void validateRelationalField(MetaField metaField) {
        String relatedModel = metaField.getRelatedModel();
        Assert.isTrue(StringUtils.isNotBlank(relatedModel),
                "{0}:{1} field, the `relatedModel` cannot be empty!",
                metaField.getModelName(), metaField.getFieldName());
        Assert.isTrue(modelMap().containsKey(relatedModel),
                "{0}:{1} field, the relatedModel `{2}` does not exist in the model metadata!",
                metaField.getModelName(), metaField.getFieldName(), relatedModel);
        if ((FieldType.TO_ONE_TYPES.contains(metaField.getFieldType()))) {
            // A TO_ONE relation joins on the related model's surrogate id ONLY. The earlier
            // reference-by-code option (a non-id `relatedField`) is removed — rich masters
            // (Currency / CountryRegion / ...) are code-as-id instead, so the FK still stores the
            // portable code while the join stays id-native. A declared non-id relatedField is rejected.
            Assert.isTrue(StringUtils.isBlank(metaField.getRelatedField())
                            || ModelConstant.ID.equals(metaField.getRelatedField()),
                    "{0}:{1} field, relatedField `{2}` is not allowed: a TO_ONE relation must join on the "
                            + "related model `{3}` by its id (reference-by-code was removed; make the "
                            + "related model code-as-id to store a business code).",
                    metaField.getModelName(), metaField.getFieldName(), metaField.getRelatedField(), relatedModel);
            metaField.setRelatedField(ModelConstant.ID);
            // delete-strategy guards (apply to any TO_ONE onDelete):
            OnDelete onDelete = metaField.getOnDelete();
            if (onDelete != null) {
                Assert.notTrue(OnDelete.SET_NULL == onDelete && metaField.isRequired(),
                        "{0}:{1} field, onDelete=SET_NULL requires a nullable FK (required=false)!",
                        metaField.getModelName(), metaField.getFieldName());
                // Timeline targets are allowed: onDelete fires on entity deletion (deleteByIds =
                // all slices of the logical id); slice-level deleteBySliceId keeps the entity
                // alive and deliberately does not trigger it.
                Assert.notTrue(OnDelete.CASCADE == onDelete && isSoftDeleted(relatedModel)
                                && !isSoftDeleted(metaField.getModelName()),
                        "{0}:{1} field, onDelete=CASCADE from soft-delete `{2}` to hard-delete `{0}` is not "
                                + "allowed — a recoverable parent must not trigger an irreversible child delete. "
                                + "Make `{0}` soft-delete too, or use onDelete=RESTRICT / SET_NULL.",
                        metaField.getModelName(), metaField.getFieldName(), relatedModel);
                Assert.notTrue(OnDelete.CASCADE == onDelete && !isMultiTenantModel(relatedModel)
                                && isMultiTenantModel(metaField.getModelName()),
                        "{0}:{1} field, onDelete=CASCADE from shared (non-multi-tenant) `{2}` to multi-tenant "
                                + "`{0}` is not allowed — deleting one shared row would cascade across ALL "
                                + "tenants. Use onDelete=RESTRICT.",
                        metaField.getModelName(), metaField.getFieldName(), relatedModel);
            }
        } else if (FieldType.ONE_TO_MANY.equals(metaField.getFieldType())) {
            Assert.notBlank(metaField.getRelatedField(),
                    "{0}:{1} is a OneToMany field, the `relatedField` cannot be empty!",
                    metaField.getModelName(), metaField.getFieldName());
            Assert.notEqual(metaField.getRelatedField(), ModelConstant.ID,
                    "{0}:{1} field, the `relatedField` cannot be `id`!",
                    metaField.getModelName(), metaField.getFieldName());
            Assert.isTrue(modelFields().get(relatedModel).containsKey(metaField.getRelatedField()),
                    "{0}:{1} is a OneToMany field, the relatedModel `{2}` does not contain the related field `{3}`!",
                    metaField.getModelName(), metaField.getFieldName(), relatedModel, metaField.getRelatedField());
        } else if (FieldType.MANY_TO_MANY.equals(metaField.getFieldType())) {
            Assert.notBlank(metaField.getJoinModel(),
                    "{0}:{1} is a ManyToMany field, the `joinModel` cannot be empty!",
                    metaField.getModelName(), metaField.getFieldName());
            Assert.isTrue(modelMap().containsKey(metaField.getJoinModel()),
                    "{0}:{1} is a ManyToMany field, the joinModel `{2}` does not exist in the model metadata!",
                    metaField.getModelName(), metaField.getFieldName(), metaField.getJoinModel());
            Assert.notBlank(metaField.getJoinLeft(),
                    "{0}:{1} is a ManyToMany field, the `joinLeft` cannot be empty!",
                    metaField.getModelName(), metaField.getFieldName());
            Assert.isTrue(modelFields().get(metaField.getJoinModel()).containsKey(metaField.getJoinLeft()),
                    "{0}:{1} is a ManyToMany field, the joinModel `{2}` does not contain the joinLeft field `{3}`!",
                    metaField.getModelName(), metaField.getFieldName(), metaField.getJoinModel(), metaField.getJoinLeft());
            Assert.notBlank(metaField.getJoinRight(),
                    "{0}:{1} is a ManyToMany field, the `joinRight` cannot be empty!",
                    metaField.getModelName(), metaField.getFieldName());
            Assert.isTrue(modelFields().get(metaField.getJoinModel()).containsKey(metaField.getJoinRight()),
                    "{0}:{1} is a ManyToMany field, the joinModel `{2}` does not contain the joinRight field `{3}`!",
                    metaField.getModelName(), metaField.getFieldName(), metaField.getJoinModel(), metaField.getJoinRight());
        }
    }

    /**
     * Check whether the `cascadedField` config is valid: `fieldA.fieldB`,
     * where `fieldA` is a ManyToOne/OneToOne field of the current model,
     * and `fieldB` is a stored field of the related model.
     * If current field is a stored field, the ManyToOne/OneToOne `fieldA` must be a stored field too.
     *
     * @param metaField field metadata object
     */
    private static void verifyCascadedField(MetaField metaField) {
        String modelName = metaField.getModelName();
        String fieldName = metaField.getFieldName();
        Assert.isTrue(!ModelConstant.AUDIT_FIELDS.contains(fieldName),
                "The field {0} of model {1} is an audit field and cannot be defined as a cascaded field!",
                fieldName, modelName);
        String[] cascadedFields = StringUtils.split(metaField.getCascadedField(), ".");
        Assert.isTrue(cascadedFields.length == 2,
                "The `cascadedField` {0} of model {1} field {2} does not valid! Only `fieldA.fieldB` format is allowed.",
                metaField.getCascadedField(), modelName, fieldName);
        MetaField leftField = getModelField(modelName, cascadedFields[0]);
        Set<String> modelAllFields = modelFields().get(modelName).keySet();
        Assert.isTrue(modelAllFields.contains(cascadedFields[0]) && FieldType.TO_ONE_TYPES.contains(leftField.getFieldType()),
                "The `cascadedField` {0} of model {1} field {2} does not valid! The field `{3}` is not a ManyToOne/OneToOne field of current model.",
                metaField.getCascadedField(), modelName, fieldName, cascadedFields[0]);
        Assert.isTrue(isStored(leftField.getRelatedModel(), cascadedFields[1]),
                "The `cascadedField` {0} of model {1} field {2} does not valid! The field `{3}` is a dynamic field of related model `{4}`.",
                metaField.getCascadedField(), modelName, fieldName, cascadedFields[1], leftField.getRelatedModel());
        metaField.setDependentFields(Arrays.asList(cascadedFields));
        if (!metaField.isDynamic()) {
            // Update the stored cascaded fields cache of the model
            MetaModel metaModel = modelMap().get(modelName);
            metaModel.addStoredCascadedField(metaField);
        }
    }

    /**
     * Check whether the dependent fields of the computed field belong to the same model.
     * Computed fields only used in single model calculations.
     * If the computed field is stored, it must depend on stored fields.
     *
     * @param metaField field metadata object
     */

    private static void verifyComputedField(MetaField metaField) {
        Assert.isTrue(!ModelConstant.AUDIT_FIELDS.contains(metaField.getFieldName()),
                "The field {0} of model {1} is an audit field and cannot be defined as a computed field!",
                metaField.getFieldName(), metaField.getModelName());
        Assert.notBlank(metaField.getExpression(),
                "The formula of computed field {0}:{1} cannot be empty!",
                metaField.getModelName(), metaField.getFieldName());
        try {
            // Extract and set `dependentFields` for computed fields
            List<String> dependentFields = ComputeUtils.getVariables(metaField.getExpression());
            validateModelFields(metaField.getModelName(), dependentFields);
            metaField.setDependentFields(dependentFields);
        } catch (ValidationException e) {
            throw new IllegalArgumentException("Computed field {0}:{1}, formula syntax error: {2}\n{3}",
                    metaField.getModelName(), metaField.getFieldName(), metaField.getExpression(), e.getMessage());
        }
        if (!metaField.isDynamic()) {
            // Stored computed field, must depend on stored fields.
            validateStoredFields(metaField.getModelName(), metaField.getDependentFields());
            // Update the stored computed fields cache of the model
            MetaModel metaModel = modelMap().get(metaField.getModelName());
            metaModel.addStoredComputedField(metaField);
        }
    }

    /**
     * Check if the fields of the model are stored fields
     *
     * @param modelName model name
     * @param fields field name list
     */
    public static void validateStoredFields(String modelName, List<String> fields) {
        Set<String> dynamicFields = fields.stream().filter(f -> getModelField(modelName, f).isDynamic())
                .collect(Collectors.toSet());
        Assert.isTrue(CollectionUtils.isEmpty(dynamicFields),
                "Not all fields {1} of model {0} are stored fields!", dynamicFields, modelName);
    }

    /**
     * Check if the fields can be referenced in GROUP BY / SPLIT BY contexts.
     * Allowed: stored fields and dynamic cascaded fields (the latter expand to a stored
     * column on the related model via LEFT JOIN at SQL build time).
     * Rejected: dynamic computed fields, OneToMany/ManyToMany, and any other dynamic types.
     *
     * @param modelName model name
     * @param fields field name list
     */
    public static void validateGroupableFields(String modelName, List<String> fields) {
        Set<String> invalidFields = fields.stream()
                .filter(f -> {
                    MetaField mf = getModelField(modelName, f);
                    return mf.isDynamic() && !mf.isDynamicCascadedField();
                })
                .collect(Collectors.toSet());
        Assert.isTrue(CollectionUtils.isEmpty(invalidFields),
                "Fields {1} of model {0} cannot be used in GROUP BY: only stored fields or dynamic cascaded fields are allowed!",
                modelName, invalidFields);
    }

    /**
     * Verify and update the `readonly` attribute of the field.
     * The `readonly` attribute is automatically set for the following fields:
     *     1. Fields in the `AUDIT_FIELDS` list, including audit fields, `version`, `sliceId`, `tenantId`.
     *     2. TenantId field if enable multi-tenant.
     *     3. Version field of the optimistic lock control model.
     *     4. SliceId field of the timeline model.
     *     5. Computed fields, cascaded fields.
     *     6. `id` field of the model, except for EXTERNAL_ID strategy.
     *     7. FILE and MULTI_FILE fields are readonly.
     *
     * @param metaField field metadata object
     */
    private static void verifyReadonlyAttribute(MetaField metaField) {
        String model = metaField.getModelName();
        if (ModelConstant.AUDIT_FIELDS.contains(metaField.getFieldName())) {
            metaField.setReadonly(true);
        } else if (SystemConfig.env.isEnableMultiTenancy() && ModelConstant.TENANT_ID.equals(metaField.getFieldName())) {
            metaField.setReadonly(true);
        } else if (modelMap().get(model).isVersionLock() && ModelConstant.VERSION.equals(metaField.getFieldName())) {
            metaField.setReadonly(true);
        } else if (modelMap().get(model).isTimeline() && ModelConstant.SLICE_ID.equals(metaField.getFieldName())) {
            metaField.setReadonly(true);
        } else if (metaField.isComputed() || StringUtils.isNotBlank(metaField.getCascadedField())) {
            metaField.setReadonly(true);
        } else if (ModelConstant.ID.equals(metaField.getFieldName())
                && !IdStrategy.EXTERNAL_ID.equals(modelMap().get(model).getIdStrategy())) {
            metaField.setReadonly(true);
        }
    }

    /**
     * Verify and update the `dynamic` attribute of the field.
     * If the field is a OneToMany/ManyToMany field, set `dynamic = true`.
     *
     * @param metaField field metadata object
     */
    private static void verifyDynamicAttribute(MetaField metaField) {
        if (FieldType.TO_MANY_TYPES.contains(metaField.getFieldType())) {
            metaField.setDynamic(true);
        }
    }

    /**
     * Check if the model exists
     *
     * @param modelName model name
     * @return true or false
     */
    public static boolean existModel(String modelName) {
        return modelMap().containsKey(modelName);
    }

    /**
     * Check if the model exists, if not, throw an exception
     * @param modelName model name
     */
    public static void validateModel(String modelName) {
        Assert.notBlank(modelName, "Model name cannot be empty!");
        if (!existModel(modelName)) {
            throw new IllegalArgumentException("Model {0} does not exist in the model metadata!", modelName);
        }
    }

    /**
     * Check if the specified field exists, if not, throw an exception
     * @param modelName model name
     * @param fieldName field name
     */
    public static void validateModelField(String modelName, String fieldName) {
        validateModel(modelName);
        Assert.isTrue(modelFields().get(modelName).containsKey(fieldName),
                "Model {0} does not exist field {1}!", modelName, fieldName);
    }

    /**
     * Check if all the specified fields exist in the model, if not, throw an exception.
     *
     * @param modelName model name
     * @param fields field name list
     */
    public static void validateModelFields(String modelName, Collection<String> fields) {
        validateModel(modelName);
        if (CollectionUtils.isEmpty(fields)) {
            return;
        }
        Set<String> accessFields = new HashSet<>(fields);
        accessFields.removeAll(modelFields().get(modelName).keySet());
        Assert.isTrue(accessFields.isEmpty(), "Model {0} does not exist fields {1}!", modelName, accessFields);
    }

    /**
     * Get the model metadata object by model name
     *
     * @param modelName model name
     * @return model metadata object
     */
    public static MetaModel getModel(String modelName) {
        validateModel(modelName);
        return modelMap().get(modelName);
    }

    /**
     * Look up an index by its globally-unique name, or null if none. Backs the friendly
     * duplicate-key resolver: a violated index name resolves to its member fields and
     * optional custom message.
     *
     * @param indexName the globally-unique index name
     * @return the index metadata, or null if no such index exists
     */
    public static MetaIndex getIndex(String indexName) {
        return indexMap().get(indexName);
    }

    /**
     * Get the tableName by model name
     *
     * @param modelName model name
     * @return tableName
     */
    public static String getModelTable(String modelName) {
        return getModel(modelName).getTableName();
    }

    /**
     * Get the model primary key field name, the physical primary key field of the timeline model is `sliceId`,
     * and the primary key field of other models is `id`.
     *
     * @param modelName model name
     * @return primary key field name
     */
    public static String getModelPrimaryKey(String modelName) {
        return isTimelineModel(modelName) ? ModelConstant.SLICE_ID : ModelConstant.ID;
    }

    /**
     * Get the model primary key field object, the physical primary key of the timeline model is `sliceId`,
     * and the primary key of other models is `id`.
     *
     * @param modelName model name
     * @return primary key field object
     */
    public static MetaField getModelPrimaryKeyField(String modelName) {
        return modelFields().get(modelName).get(isTimelineModel(modelName) ?
                ModelConstant.SLICE_ID : ModelConstant.ID);
    }

    /**
     * Get the MetaField object collection of the model.
     *
     * @param modelName model name
     * @return MetaField object collection
     */
    public static List<MetaField> getModelFields(String modelName) {
        validateModel(modelName);
        return List.copyOf(modelFields().get(modelName).values());
    }

    /**
     * Get the MetaField name String collection of the model.
     *
     * @param modelName model name
     * @return MetaField name String collection
     */
    public static List<String> getModelFieldNames(String modelName) {
        return getModelFields(modelName)
                .stream()
                .map(MetaField::getFieldName)
                .collect(Collectors.toList());
    }

    /**
     * Check whether the model itself allows copy operations
     * ({@code @Model(copyable = ...)}). Field-level filtering is a separate
     * concern handled by {@link #getModelCopyableFields(String)}.
     *
     * @param modelName model name
     * @return true if rows of the model may be duplicated
     */
    public static boolean isCopyableModel(String modelName) {
        validateModel(modelName);
        return modelMap().get(modelName).isCopyable();
    }

    /**
     * Get the copyable fields of the specified model.
     * <p>Excludes fields marked {@code copyable = false}, audit fields, dynamic fields
     * (OneToMany / ManyToMany / computed / cascaded — they are derived, not stored),
     * OneToOne fields (the related row is owned by the source row; copying the FK
     * would make two rows share one exclusively-owned related row), and the identity
     * keys {@code id} / {@code externalId}. A copy is always a NEW entity, so it must
     * never carry the source's id. For a timeline model this excludes ALL structural
     * timeline keys ({@code id} / {@code sliceId} / the effective dates): the copy then
     * becomes a fresh logical id with a genesis slice at the current date, rather than a
     * spurious slice grafted onto the source entity's own timeline.</p>
     *
     * @param modelName model name
     * @return copyable fields
     */
    public static List<String> getModelCopyableFields(String modelName) {
        boolean isTimeline = isTimelineModel(modelName);
        return modelFields().get(modelName).values().stream()
                .filter(metaField -> {
                    String fieldName = metaField.getFieldName();
                    if (!metaField.isCopyable() || metaField.isDynamic()
                            || FieldType.ONE_TO_ONE.equals(metaField.getFieldType())
                            || ModelConstant.AUDIT_FIELDS.contains(fieldName)) {
                        return false;
                    } else if (isTimeline) {
                        // Exclude every timeline structural key (id / sliceId / effective dates):
                        // a copy is a new entity, so createSlices must see no id → fresh id + genesis slice.
                        return !ModelConstant.TIMELINE_FIELDS.contains(fieldName);
                    } else return !ModelConstant.ID.equals(fieldName) && !ModelConstant.EXTERNAL_ID.equals(fieldName);
                })
                .map(MetaField::getFieldName)
                .toList();
    }

    /**
     * Get the MetaField object by model name and field name.
     *
     * @param modelName model name
     * @param fieldName field name
     * @return MetaField object
     */
    public static MetaField getModelField(String modelName, String fieldName) {
        validateModelField(modelName, fieldName);
        return modelFields().get(modelName).get(fieldName);
    }

    /**
     * Get the MetaField by model name and field name, returning null when either
     * the model is not registered or the field does not exist.
     * Useful for callers that need to opportunistically look up a field without
     * tripping the strict {@link #validateModelField} guard.
     *
     * @param modelName model name
     * @param fieldName field name
     * @return MetaField, or null if not found
     */
    public static MetaField getModelFieldOrNull(String modelName, String fieldName) {
        Map<String, MetaField> fields = modelFields().get(modelName);
        return fields == null ? null : fields.get(fieldName);
    }

    /**
     * Resolve the related model name for a relational FK field. Returns
     * null for scalar fields, missing metadata, non-relational field types,
     * or unknown models — callers can use this to short-circuit nested
     * recursion (e.g. mask / write-guard descending into sub-objects).
     *
     * <p>Covers all four relational kinds via {@link FieldType#RELATED_TYPES}:
     * ManyToOne / OneToOne / OneToMany / ManyToMany.
     *
     * @param modelName parent model name
     * @param fieldName field name on the parent model
     * @return related model name, or null when the field is not a relation
     */
    public static String resolveRelatedModel(String modelName, String fieldName) {
        if (modelName == null || fieldName == null) return null;
        if (!existModel(modelName)) return null;
        MetaField mf = getModelFieldOrNull(modelName, fieldName);
        if (mf == null || mf.getFieldType() == null) return null;
        if (!FieldType.RELATED_TYPES.contains(mf.getFieldType())) return null;
        return mf.getRelatedModel();
    }

    /**
     * Get the column name by model name and field name.
     *
     * @param modelName model name
     * @param fieldName field name
     * @return Column column name
     */
    public static String getModelFieldColumn(String modelName, String fieldName) {
        validateModelField(modelName, fieldName);
        return modelFields().get(modelName).get(fieldName).getColumnName();
    }

    /**
     * Get the translation modelName of the specified data model.
     * The translation model name equals to `modelName + "Trans"`, which stores the translations of the data model.
     *
     * @param modelName data model name
     * @return the translation modelName of the data model
     */
    public static String getTranslationModelName(String modelName) {
        validateModel(modelName);
        return modelName + ModelConstant.MODEL_TRANS_SUFFIX;
    }

    /**
     * Get the translation table name of the data model.
     * The translation table name equals to `table_name + "_trans"`, which stores the translations of the data model.
     *
     * @param modelName data model name
     * @return the translation table name of the data model
     */
    public static String getTranslationTableName(String modelName) {
        String transModel = getTranslationModelName(modelName);
        return getModelTable(transModel);
    }

    /**
     * Get the encrypted fields of the model.
     * Currently only support String fields for encryption.
     *
     * @param modelName model name
     * @return encrypted fields
     */
    public static Set<String> getModelEncryptedFields(String modelName) {
        return modelFields().get(modelName).values().stream()
                .filter(metaField -> metaField.isEncrypted() && metaField.getFieldType().equals(FieldType.STRING))
                .map(MetaField::getFieldName).collect(Collectors.toSet());
    }

    /**
     * Get the masking fields of the model.
     * Currently, only String fields are supported for masking.
     *
     * @param modelName model name
     * @return masking fields
     */
    public static Set<MetaField> getModelMaskingFields(String modelName) {
        return modelFields().get(modelName).values().stream()
                .filter(metaField -> metaField.getMaskingType() != null
                        && metaField.getFieldType().equals(FieldType.STRING))
                .collect(Collectors.toSet());
    }

    /**
     * Get the fields of the model, without OneToMany/ManyToMany fields.
     *
     * @param modelName model name
     * @return normal fields collection
     */
    public static Set<String> getModelFieldsWithoutXToMany(String modelName) {
        validateModel(modelName);
        return modelFields().get(modelName).values().stream()
                .filter(metaField -> !FieldType.TO_MANY_TYPES.contains(metaField.getFieldType()))
                .map(MetaField::getFieldName).collect(Collectors.toSet());
    }

    /**
     * Get the updatable stored fields of the single model, without OneToMany/ManyToMany and `readonly=true` fields.
     *
     * @param modelName model name
     * @return stored field collection
     */
    public static Set<String> getModelUpdatableFieldsWithoutXToMany(String modelName) {
        validateModel(modelName);
        return getModelFieldsWithoutXToMany(modelName).stream()
                .filter(field -> !getModelField(modelName, field).isReadonly())
                .collect(Collectors.toSet());
    }

    /**
     * Get the updatable fields of the model, which can be directly assigned, including the OneToMany/ManyToMany fields.
     * Excluding the fields of `readonly = true`.
     *
     * @param modelName model name
     * @return updatable field set
     */
    public static Set<String> getModelUpdatableFields(String modelName) {
        validateModel(modelName);
        return modelFields().get(modelName).values().stream()
                .filter(metaField -> !metaField.isReadonly())
                .map(MetaField::getFieldName).collect(Collectors.toSet());
    }

    /**
     * Get the stored fields of the model.
     * Excluding OneToMany/ManyToMany fields, `dynamic` cascaded fields and `dynamic` computed fields.
     *
     * @param modelName model name
     * @return stored field list
     */
    public static List<String> getModelStoredFields(String modelName) {
        validateModel(modelName);
        return modelFields().get(modelName).values().stream()
                .filter(metaField -> !metaField.isDynamic())
                .map(MetaField::getFieldName).collect(Collectors.toList());
    }

    /**
     * Get the fields of the specified fieldType in the model.
     *
     * @param modelName model name
     * @param fieldType field data type
     * @return field object set
     */
    public static Set<MetaField> getModelFieldsWithType(String modelName, FieldType fieldType) {
        validateModel(modelName);
        return modelFields().get(modelName).values().stream()
                .filter(field -> fieldType.equals(field.getFieldType()))
                .collect(Collectors.toSet());
    }

    /**
     * Get the displayed fields of the specified model.
     *
     * @param modelName model name
     * @return displayed field list
     */
    public static List<String> getModelDisplayName(String modelName) {
        List<String> displayName = getModel(modelName).getDisplayName();
        return displayName == null ? Collections.emptyList() : displayName;
    }

    /**
     * Get the numeric fields of the model, including stored and dynamic fields.
     *
     * @param modelName model name
     * @return numeric field set
     */
    public static Set<String> getModelNumericFields(String modelName) {
        validateModel(modelName);
        return modelFields().get(modelName).values().stream()
                .filter(f -> FieldType.NUMERIC_TYPES.contains(f.getFieldType())
                        && !ModelConstant.RESERVED_KEYWORD.contains(f.getFieldName()))
                .map(MetaField::getFieldName).collect(Collectors.toSet());
    }

    /**
     * Get the stored numeric fields of the model.
     *
     * @param modelName model name
     * @return stored numeric field set
     */
    public static Set<String> getModelStoredNumericFields(String modelName) {
        validateModel(modelName);
        return modelFields().get(modelName).values().stream()
                .filter(f -> FieldType.NUMERIC_TYPES.contains(f.getFieldType())
                        && !f.isDynamic()
                        && !ModelConstant.ID.equals(f.getFieldName())
                        && !ModelConstant.AUDIT_FIELDS.contains(f.getFieldName()))
                .map(MetaField::getFieldName).collect(Collectors.toSet());
    }

    /**
     * Get the last field object of the custom cascaded field.
     * Take `field1.field2.field3` as an example, get the `field3` MetaField object.
     * <p>
     * Implemented on top of {@link CascadeFieldWalker}, which enforces only the
     * structural rules (each segment exists, non-last segments are ToOne relations,
     * depth within {@link BaseConstant#CASCADE_LEVEL}). It is policy-neutral about the
     * leaf: a dynamic (non-stored) leaf — e.g. a computed field or a dynamic cascaded
     * field — is resolved and returned, not rejected, so callers such as export-header
     * and projection resolution can read its type/label. Callers that require a stored
     * leaf (e.g. SQL filter building in {@code WhereBuilder}) enforce that at their own
     * call site.
     *
     * @param modelName the main model name
     * @param fullFieldName custom cascaded field name like `field1.field2.field3`
     * @return last field object (may be a dynamic, non-stored field)
     * @throws IllegalArgumentException if the path is structurally invalid (missing
     *         segment, traversal through a non-ToOne field, or exceeds the cascade depth)
     */
    public static MetaField getLastFieldOfCascaded(String modelName, String fullFieldName) {
        CascadeFieldWalker.Result result = CascadeFieldWalker.walk(
                modelName, fullFieldName, BaseConstant.CASCADE_LEVEL, CascadeFieldWalker.Visitor.NOOP);
        if (result instanceof CascadeFieldWalker.Result.Failure failure) {
            throw new IllegalArgumentException(
                    "Custom cascaded field {0} is invalid: {1}", fullFieldName, failure.message());
        }
        return ((CascadeFieldWalker.Result.Ok) result).leaf();
    }

    /**
     * Check if the model has the specified field.
     *
     * @param modelName model name
     * @param fieldName field name
     * @return true or false
     */
    public static boolean existField(String modelName, String fieldName) {
        validateModel(modelName);
        return modelFields().get(modelName).containsKey(fieldName);
    }

    /**
     * Determine whether the model field is stored in the database. `dynamic = true` is not stored in the database,
     * including OneToMany/ManyToMany fields, dynamic cascaded field and dynamic computed field.
     *
     * @param modelName model name
     * @param fieldName field name
     * @return true or false
     */
    public static boolean isStored(String modelName, String fieldName) {
        validateModelField(modelName, fieldName);
        MetaField metaField = modelFields().get(modelName).get(fieldName);
        return !metaField.isDynamic();
    }

    /**
     * Determine whether the model is a timeline model.
     *
     * @param modelName model name
     * @return true or false
     */
    public static boolean isTimelineModel(String modelName) {
        validateModel(modelName);
        return modelMap().get(modelName).isTimeline();
    }

    /**
     * Determine whether the model is a soft delete model.
     *
     * @param modelName model name
     * @return true or false
     */
    public static boolean isSoftDeleted(String modelName) {
        return ModelManager.getModel(modelName).isSoftDelete();
    }

    /**
     * Get the soft delete field name of the model.
     */
    public static String getSoftDeleteField(String modelName) {
        return ModelManager.getModel(modelName).getSoftDeleteField();
    }

    /**
     * Determine whether the model has enabled active control.
     */
    public static boolean isActiveControl(String modelName) {
        return ModelManager.getModel(modelName).isActiveControl();
    }

    /**
     * Determine whether the model has enabled version control.
     *
     * @param modelName model name
     * @return true or false
     */
    public static boolean isVersionControl(String modelName) {
        return ModelManager.getModel(modelName).isVersionLock();
    }

    /**
     * Determine whether the model data needs to be isolated by tenantId.
     *
     * @param modelName model name
     * @return true or false
     */
    public static boolean isMultiTenantModel(String modelName) {
        return SystemConfig.env.isEnableMultiTenancy()
                && ModelManager.getModel(modelName).isMultiTenant();
    }

    /**
     * Determine whether the model data needs to be isolated by tenantId.
     *
     * @param modelName model name
     * @return true or false
     */
    public static boolean isMultiTenantControl(String modelName) {
        return SystemConfig.env.isEnableMultiTenancy()
                && ModelManager.getModel(modelName).isMultiTenant()
                && !ContextHolder.getContext().isCrossTenant();
    }

    /**
     * Determine whether the model data needs to be isolated by tenantId.
     *
     * @return true or false
     */
    public static boolean isMultiTenantControl() {
        return SystemConfig.env.isEnableMultiTenancy()
                && !ContextHolder.getContext().isCrossTenant();
    }

    /**
     * Get the ID strategy config of the model by model name.
     * If not configured, the default is DB_AUTO_ID.
     *
     * @param modelName model name
     * @return IdStrategy
     */
    public static IdStrategy getIdStrategy(String modelName) {
        IdStrategy idStrategy = ModelManager.getModel(modelName).getIdStrategy();
        return idStrategy == null ? IdStrategy.DB_AUTO_ID : idStrategy;
    }

    public static Optional<MetaField> getFieldByColumnName(String modelName, String columnName) {
        // Read-time derived fields (dynamic cascaded / computed) have no physical column;
        // their column_name may be null or blank, which must not match against any ResultSet column.
        return modelFields().get(modelName).values().stream()
                .filter(f -> columnName != null && columnName.equals(f.getColumnName()))
                .findFirst();
    }

    public static Set<String> getChildModels(String modelName) {
        validateModel(modelName);
        return new HashSet<>(modelMap().get(modelName).getChildModels());
    }
}
