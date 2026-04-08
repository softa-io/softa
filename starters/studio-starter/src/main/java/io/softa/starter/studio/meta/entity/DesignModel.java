package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.StorageType;

/**
 * DesignModel Model
 */
@Data
@Schema(name = "DesignModel")
@EqualsAndHashCode(callSuper = true)
public class DesignModel extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Portfolio")
    private Long portfolioId;

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Label Name")
    private String labelName;

    @Schema(description = "Model Name")
    private String modelName;

    @Schema(description = "Display Name")
    private List<String> displayName;

    @Schema(description = "Search Name")
    private List<String> searchName;

    @Schema(description = "Default Order")
    private Orders defaultOrder;

    @Schema(description = "Table Name")
    private String tableName;

    @Schema(description = "Enable Soft Delete")
    private Boolean softDelete;

    @Schema(description = "Soft Delete Field Name")
    private String softDeleteField;

    @Schema(description = "Enable Active Control")
    private Boolean activeControl;

    @Schema(description = "Is Timeline Model")
    private Boolean timeline;

    @Schema(description = "ID Strategy")
    private IdStrategy idStrategy;

    @Schema(description = "Storage Type")
    private StorageType storageType;

    @Schema(description = "Enable Version Lock")
    private Boolean versionLock;

    @Schema(description = "Enable Multi-tenancy")
    private Boolean multiTenant;

    @Schema(description = "Data Source")
    private String dataSource;

    @Schema(description = "Service Name")
    private String serviceName;

    @Schema(description = "Business Primary Key")
    private List<String> businessKey;

    @Schema(description = "Partition Field")
    private String partitionField;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Model Fields")
    private List<DesignField> modelFields;

    @Schema(description = "Model Indexes")
    private List<DesignModelIndex> modelIndexes;

    @Schema(description = "Deleted")
    private Boolean deleted;
}