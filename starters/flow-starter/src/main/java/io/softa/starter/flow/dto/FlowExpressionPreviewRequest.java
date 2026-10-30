package io.softa.starter.flow.dto;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * An expression or a template to evaluate over sample variables, as the designer tries it.
 */
@Schema(name = "FlowExpressionPreviewRequest")
public record FlowExpressionPreviewRequest(
        @NotBlank
        @Schema(description = "The AviatorScript expression — or, with `template`, a text with {{ }} placeholders")
        String expression,
        @Schema(description = "Sample values of the variables it reads")
        Map<String, Object> variables,
        @Schema(description = "Whether it is a template: its placeholders are interpolated, as a node's text setting is")
        boolean template) {
}
