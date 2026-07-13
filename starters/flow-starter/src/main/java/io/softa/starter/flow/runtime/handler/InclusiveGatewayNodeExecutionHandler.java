package io.softa.starter.flow.runtime.handler;

import java.util.Map;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;

/**
 * Handler for {@link FlowNodeType#INCLUSIVE_GATEWAY} nodes.
 * <p>
 * An inclusive gateway (OR gateway) selects all outgoing edges whose conditions
 * evaluate to {@code true} (N ≥ 1). The routing logic itself — evaluating each
 * outgoing edge's condition expression against the current context — is delegated
 * to the engine orchestrator.
 * </p>
 * <p>
 * This handler returns empty outputs so the engine can record a step snapshot
 * and then proceed with its own edge-condition evaluation.
 * </p>
 */
@Component
public class InclusiveGatewayNodeExecutionHandler implements NodeExecutionHandler {

    @Override
    public boolean supports(FlowNodeType flowNodeType) {
        return FlowNodeType.INCLUSIVE_GATEWAY.equals(flowNodeType);
    }

    @Override
    public NodeOutcome execute(CompiledFlowNode node, FlowVariableContext ctx) {
        return new NodeOutcome.Completed(Map.of());
    }
}
