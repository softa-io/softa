package io.softa.starter.flow.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A document the application can approve — a model with an approval handler — and the approval flows it has, told
 * apart by their submit conditions.
 */
@Schema(name = "ApprovalModelView")
public record ApprovalModelView(

        @Schema(description = "Model name")
        String modelName,

        @Schema(description = "The model's label")
        String label,

        @Schema(description = "The names of its approval flows, in any state; empty while it has none")
        List<String> flowNames
) {
}
