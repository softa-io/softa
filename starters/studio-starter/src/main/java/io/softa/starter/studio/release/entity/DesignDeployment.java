package io.softa.starter.studio.release.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.studio.release.enums.DesignDeploymentStatus;

/**
 * DesignDeployment Model — the immutable deployment record produced when a Version is deployed to an Env.
 * <p>
 * A Deployment is the single deployment artifact. It is created during the deployment process:
 * the system selects released versions in sealedTime order from the Env's
 * {@code currentVersionId} (sourceVersionId) to the target Version (targetVersionId),
 * merges their version contents via
 * {@link io.softa.starter.studio.release.version.VersionMerger}, generates DDL, and stores everything
 * on this entity along with execution results.
 * <p>
 * Lifecycle: {@code PENDING} → {@code DEPLOYING} → {@code SUCCESS} / {@code FAILURE} / {@code ROLLED_BACK}
 */
@Data
@Schema(name = "DesignDeployment")
@EqualsAndHashCode(callSuper = true)
public class DesignDeployment extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Env ID — the environment this deployment targets")
    private Long envId;

    @Schema(description = "Source Version ID — the Env's currentVersionId at deployment time (null for first deploy)")
    private Long sourceVersionId;

    @Schema(description = "Target Version ID — the version being deployed")
    private Long targetVersionId;

    @Schema(description = "Name")
    private String name;

    @Schema(description = "Deploy Status")
    private DesignDeploymentStatus deployStatus;

    @Schema(description = "Deploy Duration (S)")
    private Double deployDuration;

    @Schema(description = "Deployment Summary")
    private String summary;

    @Schema(description = "Diff Hash — SHA-256 of the serialized mergedContent")
    private String diffHash;

    @Schema(description = "Merged Content — the merged versionedContent from the sealedTime release interval")
    private JsonNode mergedContent;

    @Schema(description = "Merged Table DDL — combined DDL for table structure changes")
    private String mergedDdlTable;

    @Schema(description = "Merged Index DDL — combined DDL for index changes")
    private String mergedDdlIndex;

    @Schema(description = "Version List")
    private List<DesignDeploymentVersion> versions;

    @Schema(description = "Started Time")
    private LocalDateTime startedTime;

    @Schema(description = "Finished Time")
    private LocalDateTime finishedTime;

    @Schema(description = "Operator")
    private String operatorId;

    @Schema(description = "Error Message")
    private String errorMessage;

    @Schema(description = "Deleted")
    private Boolean deleted;
}
