package io.softa.starter.flow.runtime.nodeconfig;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.NodeErrorStrategy;

/**
 * Unified error-handling configuration for any node.
 * <p>
 * Replaces the former pair of loose fields {@code errorStrategy} + {@code retryCount}
 * on {@code CompiledFlowNode} with a typed, cohesive config record.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeErrorConfig {

    /** How to handle a node execution error (default: {@link NodeErrorStrategy#FAIL}). */
    @Builder.Default
    private NodeErrorStrategy strategy = NodeErrorStrategy.FAIL;

    /** Number of immediate retry attempts before giving up (only used when strategy is RETRY). */
    @Builder.Default
    private int retryCount = 0;

    /** Convenience factory that returns the default fail-fast config. */
    public static NodeErrorConfig failFast() {
        return NodeErrorConfig.builder().build();
    }

}
