package io.softa.starter.studio.release.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.studio.release.enums.DesignWorkItemStatus;

/**
 * DesignWorkItem Model
 */
@Data
@Schema(name = "DesignWorkItem")
@EqualsAndHashCode(callSuper = true)
public class DesignWorkItem extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Name")
    private String name;

    @Schema(description = "Status")
    private DesignWorkItemStatus status;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Closed Time — when the WorkItem was released to prod.")
    private LocalDateTime closedTime;

    @Schema(description = "Version ID — the version this WorkItem belongs to (null if not yet added to a version)")
    private Long versionId;

    @Schema(description = "Deleted")
    private Boolean deleted;
}
