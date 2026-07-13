package io.softa.starter.flow.runtime.api;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request for the Validate endpoint that transiently evaluates Validation-scenario
 * flows bound to a model and returns each flow's declared outputs.
 */
@Data
@Schema(name = "FlowValidationRequest")
public class FlowValidationRequest {

    @NotBlank(message = "sourceModel must not be blank")
    @Schema(description = "Source model name")
    private String sourceModel;

    @Schema(description = "Candidate row data to validate")
    private Map<String, Object> data;

    @Schema(description = "Change type driving trigger event filters (CREATE / UPDATE / DELETE); "
            + "empty matches only triggers with no event filter")
    private String changeType;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(description = "Actor who evaluates the validation flows", accessMode = Schema.AccessMode.READ_ONLY)
    private String actorId;
}
