package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.enums.FlowScenario;

/**
 * Editor auto-save request. Saving never runs semantic validation — a draft is
 * allowed to be broken; validation is an explicit separate call.
 */
@Schema(name = "FlowDesignSaveRequest")
public record FlowDesignSaveRequest(

        @NotNull
        @Schema(description = "Full canvas document to store")
        DesignFlowDefinition designJson,

        @Schema(description = "New display name; unchanged when omitted")
        String flowName,

        @Schema(description = "New scenario; unchanged when omitted")
        FlowScenario scenario,

        @NotNull
        @Schema(description = "Optimistic-lock version as loaded; a mismatch rejects the save "
                + "so concurrent editor sessions cannot silently overwrite each other")
        Integer version
) {
}
