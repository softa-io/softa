package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Paged instance search for monitoring views. Results are summary rows — the
 * heavy JSON state columns and the trace are excluded; fetch a single instance
 * (or its overlay) for detail.
 */
@Schema(name = "FlowInstanceSearchRequest")
public record FlowInstanceSearchRequest(

        @Schema(description = "Filter by flow code")
        String flowCode,

        @Schema(description = "Filter by design id")
        Long designId,

        @Schema(description = "Filter by execution status")
        FlowExecutionStatus status,

        @Schema(description = "Filter by initiator id")
        String initiatorId,

        @Schema(description = "Filter by related model name")
        String modelName,

        @Schema(description = "Filter by related row id (requires modelName)")
        String rowId,

        @Schema(description = "1-based page number; default 1")
        Integer pageNumber,

        @Schema(description = "Page size; default 50")
        Integer pageSize
) {
}
