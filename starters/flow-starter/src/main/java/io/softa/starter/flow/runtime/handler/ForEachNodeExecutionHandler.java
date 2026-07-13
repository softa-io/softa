package io.softa.starter.flow.runtime.handler;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;

/**
 * Handler for {@link FlowNodeType#FOR_EACH} nodes.
 * <p>
 * <strong>Not yet supported.</strong> Driving child-node iteration requires the
 * orchestrator to execute the loop body ({@code config.childNodeIds}) once per
 * resolved item — analogous to {@code executeSubflow} — including a defined
 * semantic for a body node that suspends (approval/timer/async), mirroring the
 * synchronous-subflow constraint. That orchestration does not exist
 * yet, so this handler fails fast rather than silently
 * skipping the body. FOR_EACH is also rejected at compile time by
 * {@code UnsupportedNodeTypeValidator} and is not offered in the node palette, so this
 * path is defence-in-depth and should be unreachable for published flows.
 */
@Component
public class ForEachNodeExecutionHandler implements NodeExecutionHandler {

    @Override
    public boolean supports(FlowNodeType flowNodeType) {
        return FlowNodeType.FOR_EACH.equals(flowNodeType);
    }

    @Override
    public NodeOutcome execute(CompiledFlowNode node, FlowVariableContext ctx) {
        throw new FlowRuntimeException("ForEach node '" + node.getNodeId()
                + "' is not supported: the runtime does not execute loop child nodes yet. "
                + "This node should have been rejected at compile time.");
    }
}
