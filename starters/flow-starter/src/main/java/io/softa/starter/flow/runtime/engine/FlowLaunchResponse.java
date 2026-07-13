package io.softa.starter.flow.runtime.engine;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Combined launch/debug response that includes the resolved bundle and runtime state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowLaunchResponse")
public class FlowLaunchResponse {

    @Schema(description = "Resolved compiled flow bundle")
    private CompiledFlowDefinition bundle;

    @Schema(description = "Runtime execution state")
    private FlowExecutionState state;
}

