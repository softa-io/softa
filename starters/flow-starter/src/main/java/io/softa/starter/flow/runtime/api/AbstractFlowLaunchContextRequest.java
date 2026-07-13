package io.softa.starter.flow.runtime.api;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Base request carrying launch initiator and runtime variables.
 * <p>
 * {@code initiatorId} is resolved server-side from the authenticated user context
 * and cannot be set via the REST API request body.
 */
@Data
public abstract class AbstractFlowLaunchContextRequest {

    @Schema(description = "Initiator id — resolved from login context, ignored if sent in request body",
            accessMode = Schema.AccessMode.READ_ONLY)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String initiatorId;

    @Schema(description = "Initial runtime variables")
    private Map<String, Object> variables;
}

