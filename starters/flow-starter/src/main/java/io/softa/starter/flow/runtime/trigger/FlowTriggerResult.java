package io.softa.starter.flow.runtime.trigger;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Result of triggering a single flow via the automation service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowTriggerResult")
public class FlowTriggerResult {

    @Schema(description = "Flow code that was triggered")
    private String flowCode;

    @Schema(description = "Whether the flow was triggered successfully")
    private boolean success;

    @Schema(description = "Execution state when available")
    private FlowExecutionState state;

    @Schema(description = "Error message when the flow failed to start")
    private String error;
}

