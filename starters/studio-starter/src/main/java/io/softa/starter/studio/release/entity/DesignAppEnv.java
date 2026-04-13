package io.softa.starter.studio.release.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.studio.release.enums.DesignAppEnvType;

/**
 * DesignAppEnv Model — represents a deployment environment for a DesignApp.
 * <p>
 * Each Env tracks its own version state via {@code currentVersionId}, which points to
 * the latest version that has been successfully deployed to this environment.
 * When deploying a new Version, the system merges released versions in the
 * sealedTime interval {@code (currentVersionId, targetVersion]} to produce the Deployment.
 */
@Data
@Schema(name = "DesignAppEnv")
@EqualsAndHashCode(callSuper = true)
public class DesignAppEnv extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Current Version ID — the latest version successfully deployed to this env")
    private Long currentVersionId;

    @Schema(description = "Env Name")
    private String name;

    @Schema(description = "Sequence")
    private Integer sequence;

    @Schema(description = "Env Type")
    private DesignAppEnvType envType;

    @Schema(description = "Protected Env")
    private Boolean protectedEnv;

    @Schema(description = "Active")
    private Boolean active;

    @Schema(description = "Upgrade API EndPoint")
    private String upgradeEndpoint;

    @Schema(description = "Client ID")
    private String clientId;

    @Schema(description = "Client Secret")
    private String clientSecret;

    @Schema(description = "Async Upgrade")
    private Boolean asyncUpgrade;

    @Schema(description = "Auto Upgrade")
    private Boolean autoUpgrade;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Deleted")
    private Boolean deleted;
}
