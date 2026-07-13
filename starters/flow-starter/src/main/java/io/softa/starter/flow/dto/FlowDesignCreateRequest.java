package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.enums.FlowScenario;

/**
 * Editor request to create a new flow draft.
 */
@Schema(name = "FlowDesignCreateRequest")
public record FlowDesignCreateRequest(

        @NotBlank
        @Schema(description = "Flow display name")
        String flowName,

        @NotBlank
        @Schema(description = "Flow code (business identifier, unique per tenant, immutable after create)")
        String flowCode,

        @NotNull
        @Schema(description = "Execution scenario")
        FlowScenario scenario,

        @Schema(description = "Optional initial canvas; an empty canvas is created when omitted")
        DesignFlowDefinition designJson
) {
}
