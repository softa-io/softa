package io.softa.starter.studio.release.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.starter.studio.release.enums.DesignAppStatus;

/**
 * DesignApp Model
 */
@Data
@Schema(name = "DesignApp")
@EqualsAndHashCode(callSuper = true)
public class DesignApp extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Portfolio")
    private Long portfolioId;

    @Schema(description = "Owner")
    private Long ownerId;

    @Schema(description = "App Name")
    private String appName;

    @Schema(description = "App Code")
    private String appCode;

    @Schema(description = "App Type")
    private String appType;

    @Schema(description = "Database Type")
    private DatabaseType databaseType;

    @Schema(description = "Package Name")
    private String packageName;

    @Schema(description = "App Status")
    private DesignAppStatus status;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Deleted")
    private Boolean deleted;
}
