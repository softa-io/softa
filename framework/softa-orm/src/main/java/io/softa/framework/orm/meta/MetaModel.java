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

    private Long appId;

    private String labelName;

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

    private boolean softDelete;

    // Compatible with different soft delete field names of historical systems, such as "is_deleted"
    private String softDeleteField;

    private boolean activeControl;

    private boolean versionLock;

    private boolean multiTenant;

    private String dataSource;

    private String serviceName;

    @Setter(AccessLevel.NONE)
    private List<String> businessKey;

    private String partitionField;

    /** Advance attributes */
    @Setter(AccessLevel.NONE)
    private List<MetaField> storedComputedFields = new ArrayList<>();

    @Setter(AccessLevel.NONE)
    private List<MetaField> storedCascadedFields = new ArrayList<>();

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

    protected void addAuditCreateField(String fieldName) {
        this.auditCreateFields.add(fieldName);
    }

    protected void addAuditUpdateField(String fieldName) {
        this.auditUpdateFields.add(fieldName);
    }

    protected void addChildModel(String modelName) {
        this.childModels.add(modelName);
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
    }


}