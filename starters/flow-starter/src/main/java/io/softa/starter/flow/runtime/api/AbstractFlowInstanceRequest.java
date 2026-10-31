package io.softa.starter.flow.runtime.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Base request carrying a runtime instance id, the actor performing the action,
 * and an optional comment.
 * <p>
 * {@code actorId} is resolved server-side from the authenticated user context
 * and cannot be set via the REST API request body.
 */
@Data
public abstract class AbstractFlowInstanceRequest {

    @NotBlank(message = "instanceId must not be blank")
    @Schema(description = "Instance id")
    private String instanceId;

    @Schema(description = "Actor id — resolved from login context, ignored if sent in request body",
            accessMode = Schema.AccessMode.READ_ONLY)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String actorId;

    // The limit a batch action's comment has, and an administrator's reason.
    @Size(max = 256, message = "The comment can be at most 256 characters")
    @Schema(description = "Optional action comment, at most 256 characters")
    private String comment;
}
