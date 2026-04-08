package io.softa.starter.studio.release.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.studio.release.enums.DesignAppVersionStatus;
import io.softa.starter.studio.release.enums.DesignAppVersionType;

/**
 * DesignAppVersion Model — represents a unit of change that can enter the release stream.
 * <p>
 * A Version contains the aggregated changelog data from its WorkItems, captured at seal time.
 * Deployment order is determined by version status, sealedTime, and the environment's
 * currentVersionId. DRAFT versions do not participate in deployment merge until sealed.
 */
@Data
@Schema(name = "DesignAppVersion")
@EqualsAndHashCode(callSuper = true)
public class DesignAppVersion extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Version Name")
    private String name;

    @Schema(description = "Version Type: Normal or Hotfix")
    private DesignAppVersionType versionType;

    @Schema(description = "Upgrade description")
    private String description;

    @Schema(description = "Version Content — aggregated changelog from WorkItems, captured at seal time")
    private JsonNode versionedContent;

    @Schema(description = "Diff Hash — SHA-256 of the serialized versionedContent for integrity verification")
    private String diffHash;

    @Schema(description = "Status")
    private DesignAppVersionStatus status;

    @Schema(description = "Sealed Time")
    private LocalDateTime sealedTime;

    @Schema(description = "Frozen Time")
    private LocalDateTime frozenTime;

    @Schema(description = "Deleted")
    private Boolean deleted;
}
