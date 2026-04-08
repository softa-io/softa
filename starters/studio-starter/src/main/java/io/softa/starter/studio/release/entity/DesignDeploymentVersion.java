package io.softa.starter.studio.release.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * DesignDeploymentVersion Model — audit record linking a Deployment to the Versions it merged.
 * <p>
 * These records are auto-generated when a Deployment is created from the sealedTime release interval.
 * The sequence reflects the order in which versions were applied during the merge
 * (ascending by sealedTime up to the target version).
 */
@Data
@Schema(name = "DesignDeploymentVersion")
@EqualsAndHashCode(callSuper = true)
public class DesignDeploymentVersion extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Deployment ID")
    private Long deploymentId;

    @Schema(description = "Version ID")
    private Long versionId;

    @Schema(description = "Merge sequence — ascending order in which versions were applied")
    private Integer sequence;
}
