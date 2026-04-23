package io.softa.starter.studio.release.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * DesignAppEnvSnapshot Model — stores a full metadata snapshot for a DesignAppEnv.
 * <p>
 * Each successful deployment produces one snapshot row, uniquely keyed by
 * {@code (appId, envId, deploymentId)} (UNIQUE index enforced at the DB level).
 * The snapshot records the expected full state of runtime metadata at deployment time,
 * enabling drift detection between design-time and runtime, and per-deployment rollback.
 * <p>
 * The "current" snapshot for an env is the one belonging to the latest deployment
 * (ordered by id / deploymentId DESC).
 */
@Data
@Schema(name = "DesignAppEnvSnapshot")
@EqualsAndHashCode(callSuper = true)
public class DesignAppEnvSnapshot extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Env ID — the environment this snapshot belongs to (OneToOne)")
    private Long envId;

    @Schema(description = "Deployment ID — the deployment that produced this snapshot")
    private Long deploymentId;

    @Schema(description = "Snapshot — full expected state of runtime metadata keyed by model name")
    private JsonNode snapshot;

    @Schema(description = "Deleted")
    private Boolean deleted;
}

