package io.softa.starter.flow.runtime.api;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request for the Onchange endpoint that evaluates field-change flows
 * and returns the computed variables diff.
 */
@Data
@Schema(name = "FlowOnchangeRequest")
public class FlowOnchangeRequest {

    @NotBlank(message = "sourceModel must not be blank")
    @Schema(description = "Source model name")
    private String sourceModel;

    @Schema(description = "Changed field values (field name → new value)")
    private Map<String, Object> fieldChanges;

    @Schema(description = "Full current row data before the change")
    private Map<String, Object> currentData;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(description = "Actor who evaluates the onchange flow", accessMode = Schema.AccessMode.READ_ONLY)
    private String actorId;
}
