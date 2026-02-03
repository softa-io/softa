package io.softa.framework.orm.meta;

import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.StorageType;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;

/**
 * MetaModel object
 */
@Data
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
    private String defaultOrder;

    // Display name fields
    private List<String> displayName;

    // Search name fields
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

    private List<String> businessKey;

    private String partitionField;

    /** Advance attributes */
    private List<MetaField> storedComputedFields = new ArrayList<>();

    private List<MetaField> storedCascadedFields = new ArrayList<>();

    private Set<String> auditCreateFields = new HashSet<>();

    private Set<String> auditUpdateFields = new HashSet<>();

}