package io.softa.framework.orm.meta;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import lombok.*;

import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.StorageType;

/**
 * MetaModel object
 */
@Getter
@Setter(AccessLevel.PACKAGE)
@ToString
@EqualsAndHashCode
public class MetaModel implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String label;

    private String modelName;

    private String tableName;

    private IdStrategy idStrategy;

    private StorageType storageType;

    // Model level default orders, such as "name ASC"
    private Orders defaultOrder;

    // Display name fields
    @Setter(AccessLevel.NONE)
    private List<String> displayName;

    // Search name fields
    @Setter(AccessLevel.NONE)
    private List<String> searchName;

    private String description;

    private boolean timeline;

    // The flag field is always ModelConstant.SOFT_DELETED_FIELD ("deleted"); a legacy column
    // name is mapped with @Field(columnName = ...), not by renaming the logical field.
    private boolean softDelete;

    private boolean activeControl;

    private boolean versionLock;

    private boolean multiTenant;

    /** Rows are partitioned by country; reads are narrowed to the context's selected company country. */
    private boolean multiCountry;

    /** Rows belong to one company; reads are narrowed to the context's selected company. */
    private boolean multiCompany;

    // Default true: the sys_model column is NOT NULL DEFAULT 1; the initializer
    // covers programmatically constructed instances (tests, in-memory models).
    private boolean copyable = true;

    /** Read-only projection over a table another model (or an external process) owns; writes are rejected. */
    private boolean projection;

    private String dataSource;

    // Owning app identity, projected from sys_model.app_code by the generic
    // meta row mapper. Drives RPC routing in SwitchServiceAspect: an operation on a model
    // whose appCode differs from this runtime's system.app-code is routed to the owning app.
    private String appCode;

    @Setter(AccessLevel.NONE)
    private List<String> businessKey;

    private String partitionField;

    /** Advance attributes */
    @Setter(AccessLevel.NONE)
    private List<MetaField> storedComputedFields = new ArrayList<>();

    @Setter(AccessLevel.NONE)
    private List<MetaField> storedCascadedFields = new ArrayList<>();

    /**
     * Fields whose {@code constraints} carry a condition ({@code requiredWhen} / {@code hiddenWhen} /
     * {@code readonlyWhen} / {@code invalidWhen}). The write pipelines walk this list instead of every
     * field: on create to evaluate each, on update to register the fields a condition reads into the
     * columns fetched from the stored row. Populated by {@code ModelManager.verifyFieldConstraints()}.
     */
    @Setter(AccessLevel.NONE)
    private List<MetaField> conditionalFields = new ArrayList<>();

    @Setter(AccessLevel.NONE)
    private Set<String> auditCreateFields = new HashSet<>();

    @Setter(AccessLevel.NONE)
    private Set<String> auditUpdateFields = new HashSet<>();

    /**
     * Child models in the aggregate root, derived from OneToMany fields with composition=true.
     * The lifecycle of child models is fully managed by this aggregate root model.
     */
    @Setter(AccessLevel.NONE)
    private Set<String> childModels = new HashSet<>();

    /**
     * OnDelete TO_ONE foreign keys pointing AT this model with a non-KEEP delete strategy
     * ({@code @Field.onDelete}): when a row of this model is deleted, these referencing
     * fields' RESTRICT/CASCADE/SET_NULL are enforced. Populated at init by
     * {@code ModelManager.identifyOnDeleteRefs()}; empty for models nobody references with a non-KEEP policy.
     */
    @Setter(AccessLevel.NONE)
    private List<MetaField> onDeleteRefFields = new ArrayList<>();

    protected void setDisplayName(List<String> displayName) {
        this.displayName = Collections.unmodifiableList(displayName);
    }

    protected void setSearchName(List<String> searchName) {
        this.searchName = Collections.unmodifiableList(searchName);
    }

    protected void addStoredComputedField(MetaField metaField) {
        this.storedComputedFields.add(metaField);
    }

    protected void addStoredCascadedField(MetaField metaField) {
        this.storedCascadedFields.add(metaField);
    }

    protected void addConditionalField(MetaField metaField) {
        this.conditionalFields.add(metaField);
    }

    protected void addAuditCreateField(String fieldName) {
        this.auditCreateFields.add(fieldName);
    }

    protected void addAuditUpdateField(String fieldName) {
        this.auditUpdateFields.add(fieldName);
    }

    protected void addChildModel(String modelName) {
        this.childModels.add(modelName);
    }

    protected void addOnDeleteRefField(MetaField metaField) {
        this.onDeleteRefFields.add(metaField);
    }

    /**
     * Seal the model fields and related attributes to make them immutable after initialization,
     * preventing accidental modification.
     */
    protected void sealModelFields() {
        this.storedComputedFields = Collections.unmodifiableList(this.storedComputedFields);
        this.storedCascadedFields = Collections.unmodifiableList(this.storedCascadedFields);
        this.auditCreateFields = Collections.unmodifiableSet(this.auditCreateFields);
        this.auditUpdateFields = Collections.unmodifiableSet(this.auditUpdateFields);
        this.childModels = Collections.unmodifiableSet(this.childModels);
        this.onDeleteRefFields = Collections.unmodifiableList(this.onDeleteRefFields);
    }


}