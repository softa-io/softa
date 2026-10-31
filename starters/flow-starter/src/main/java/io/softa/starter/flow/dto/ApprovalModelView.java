package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A document the application can approve — a model with an approval handler — and the approval flow it has, if
 * any: a document has one.
 */
@Schema(name = "ApprovalModelView")
public record ApprovalModelView(

        @Schema(description = "Model name")
        String modelName,

        @Schema(description = "The model's label")
        String label,

        @Schema(description = "Its approval flow's id; null while it has none")
        String flowId,

        @Schema(description = "Its approval flow's name; null while it has none")
        String flowName,

        @Schema(description = "Whether its approval flow is enabled; null while it has none")
        Boolean flowEnabled
) {
}
