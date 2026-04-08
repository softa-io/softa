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
 * Each Env has at most one snapshot (OneToOne), which is created or updated after a
 * successful deployment. The snapshot records the expected full state of runtime metadata
 * at deployment time, enabling drift detection between design-time and runtime.
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

