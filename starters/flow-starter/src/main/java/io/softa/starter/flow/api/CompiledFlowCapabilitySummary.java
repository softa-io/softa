package io.softa.starter.flow.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Derived capability summary for operational routing and observability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "CompiledFlowCapabilitySummary")
public class CompiledFlowCapabilitySummary {

    @Schema(description = "Whether the graph contains Approval nodes (human-centric flow)")
    private boolean hasApproval;

    @Schema(description = "Whether the graph contains Subflow nodes")
    private boolean hasSubflow;

    @Schema(description = "Whether the graph contains ParallelFork or ParallelJoin nodes")
    private boolean hasParallelGateway;

    @Schema(description = "Whether the graph contains ForEach nodes")
    private boolean hasLoop;
}
