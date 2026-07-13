package io.softa.starter.flow.runtime.handler;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;

/**
 * Handler for {@link FlowNodeType#HUMAN_TASK} nodes.
 * <p>
 * <strong>Not yet supported.</strong> A HumanTask ("someone fills a form and clicks
 * submit" — no voting semantics) needs its own wait: a dedicated wait-token type,
 * assignee resolution from {@code HumanTaskNodeConfig.assigneeExpression}, and a task
 * completion API that merges the submitted form into {@code vars}. None of that exists.
 * The previous implementation borrowed the approval wait signal, but approver
 * resolution only understands approval config, so every HumanTask node failed at
 * runtime with an empty-approver error. This handler fails fast with the real reason
 * instead. HUMAN_TASK is also rejected at compile time by
 * {@code UnsupportedNodeTypeValidator} and is not offered in the node palette, so this
 * path is defence-in-depth and should be unreachable for published flows.
 */
@Component
public class HumanTaskNodeExecutionHandler implements NodeExecutionHandler {

    @Override
    public boolean supports(FlowNodeType flowNodeType) {
        return FlowNodeType.HUMAN_TASK.equals(flowNodeType);
    }

    @Override
    public NodeOutcome execute(CompiledFlowNode node, FlowVariableContext ctx) {
        throw new FlowRuntimeException("HumanTask node '" + node.getNodeId()
                + "' is not supported: the runtime has no human-task wait yet. "
                + "This node should have been rejected at compile time.");
    }
}
