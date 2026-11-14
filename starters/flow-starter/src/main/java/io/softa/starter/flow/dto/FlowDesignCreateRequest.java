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

        @Schema(description = "An Approval flow's document: the model whose records are submitted to it, required for one "
                + "and fixed once created — its trigger is a submission of it. Any other flow names its model in its "
                + "trigger (designJson.trigger.modelName) and leaves this out")
        String modelName,

        @Schema(description = "Optional initial canvas; an empty canvas is created when omitted")
        DesignFlowDefinition designJson
) {
}
