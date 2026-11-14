package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What an expression or a template gives over sample variables, or why it does not run.
 */
@Schema(name = "FlowExpressionPreview")
public record FlowExpressionPreview(
        @Schema(description = "Whether it ran")
        boolean valid,
        @Schema(description = "What it gave")
        Object result,
        @Schema(description = "The type of what it gave: Boolean, Long, BigDecimal, String…; null for nothing")
        String resultType,
        @Schema(description = "Why it did not run")
        String message) {
}
