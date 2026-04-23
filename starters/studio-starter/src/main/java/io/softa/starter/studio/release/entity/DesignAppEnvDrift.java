package io.softa.starter.studio.release.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.studio.release.enums.DesignDriftCheckStatus;

/**
 * DesignAppEnvDrift — cached result of the last drift check for a {@code DesignAppEnv}.
 * <p>
 * One row per env (unique index on {@code env_id}). The drift comparison is an
 * expensive operation (parallel RPC to the runtime, per-row diffing); it is performed
 * automatically once after every successful deployment and on manual operator request,
 * then cached here so the public {@code compareDesignWithRuntime(envId)} endpoint
 * serves results in O(1) DB reads.
 * <p>
 * {@code driftContent} is a JSON list of {@code ModelChangesDTO} — empty when there is
 * no drift, populated otherwise. {@code hasDrift} mirrors that flag for cheap filtering
 * without deserializing the JSON.
 */
@Data
@Schema(name = "DesignAppEnvDrift")
@EqualsAndHashCode(callSuper = true)
public class DesignAppEnvDrift extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "App ID")
    private Long appId;

    @Schema(description = "Env ID (OneToOne with DesignAppEnv)")
    private Long envId;

    @Schema(description = "True when the last successful check found drift")
    private Boolean hasDrift;

    @Schema(description = "Serialized List<ModelChangesDTO> — empty when no drift")
    private JsonNode driftContent;

    @Schema(description = "Outcome of the last drift check")
    private DesignDriftCheckStatus checkStatus;

    @Schema(description = "Error message from the last failed check (null on success)")
    private String errorMessage;

    @Schema(description = "When the last drift check completed")
    private LocalDateTime lastCheckedTime;

    @Schema(description = "Deleted")
    private Boolean deleted;
}
