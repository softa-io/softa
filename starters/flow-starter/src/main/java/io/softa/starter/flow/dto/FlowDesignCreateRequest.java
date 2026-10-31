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
        @Schema(description = "Flow name, unique within the tenant")
        String flowName,

        @NotNull
        @Schema(description = "Execution scenario")
        FlowScenario scenario,

        @Schema(description = "The model whose records the flow starts on: an Approval flow's document (required), the "
                + "model a change trigger watches; omitted for a flow started on no record. Fixed once created")
        String modelName,

        @Schema(description = "Optional initial canvas; an empty canvas is created when omitted")
        DesignFlowDefinition designJson
) {
}
