package io.softa.starter.flow.runtime.handler;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;

/**
 * Default no-op handler for pure routing / control nodes that require no business logic.
 * <p>
 * Covers: {@code START}, {@code END}, {@code PARALLEL_FORK}, and {@code PARALLEL_JOIN}.
 * These nodes are handled structurally by the orchestrator; the handler simply
 * returns an empty output map so the engine can record a step snapshot and move on.
 * </p>
 */
@Component
public class PassThroughNodeExecutionHandler implements NodeExecutionHandler {

    private static final Set<FlowNodeType> SUPPORTED_TYPES = EnumSet.of(
            FlowNodeType.START,
            FlowNodeType.END,
            FlowNodeType.PARALLEL_FORK,
            FlowNodeType.PARALLEL_JOIN
    );

    @Override
    public boolean supports(FlowNodeType flowNodeType) {
        return SUPPORTED_TYPES.contains(flowNodeType);
    }

    @Override
    public NodeOutcome execute(CompiledFlowNode node, FlowVariableContext ctx) {
        return new NodeOutcome.Completed(Map.of());
    }
}
