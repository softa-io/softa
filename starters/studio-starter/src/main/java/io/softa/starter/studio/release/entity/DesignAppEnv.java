package io.softa.starter.studio.release.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.studio.release.enums.DesignAppEnvStatus;
import io.softa.starter.studio.release.enums.DesignAppEnvType;

/**
 * DesignAppEnv Model — represents a deployment environment for a DesignApp.
 * <p>
 * Each Env tracks its own version state via {@code currentVersionId}, which points to
 * the latest version that has been successfully deployed to this environment.
 * When deploying a new Version, the system merges released versions in the
 * sealedTime interval {@code (currentVersionId, targetVersion]} to produce the Deployment.
 * <p>
 * Concurrent deployments against the same env are serialized via {@code envStatus}.
 * A deployment may only start when {@code envStatus == STABLE}; it acquires the lock
 * by compare-and-set transitioning the field to {@code DEPLOYING} and releases it on
 * completion (success or failure).
 * <p>
 * Authentication between Studio and the runtime targeted by this Env uses per-env
 * Ed25519 keypairs. Studio signs outgoing upgrade requests with {@code privateKey};
 * the runtime verifies against the corresponding {@code publicKey}. Rotation scope
 * is one env at a time — reissuing a keypair here does not disturb other runtimes.
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

    @Schema(description = "Env runtime status — used as a per-env deployment mutex")
    private DesignAppEnvStatus envStatus;

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

    @Schema(description = "Public Key — Base64-encoded X.509 SubjectPublicKeyInfo; served to the runtime operator when provisioning")
    private String publicKey;

    @Schema(description = "Private Key — Base64-encoded PKCS#8; ORM-layer encrypted at rest, never returned in read responses")
    private String privateKey;

    @Schema(description = "Auto Upgrade")
    private Boolean autoUpgrade;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Deleted")
    private Boolean deleted;
}
