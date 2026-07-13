package io.softa.starter.flow.runtime.handler;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;

/**
 * Handler that pauses runtime execution for an {@link FlowNodeType#APPROVAL} node.
 * <p>
 * The approval node itself has no business logic — it signals the engine to
 * suspend the instance until an approval decision arrives via callback.
 * Approver resolution, vote rules, and timeout policies are handled by the
 * approval service layer, not this handler.
 * </p>
 */
@Component
public class ApprovalNodeExecutionHandler implements NodeExecutionHandler {

    @Override
    public boolean supports(FlowNodeType flowNodeType) {
        return FlowNodeType.APPROVAL.equals(flowNodeType);
    }

    @Override
    public NodeOutcome execute(CompiledFlowNode node, FlowVariableContext ctx) {
        // toExpressionScope() is available for downstream use (e.g. evaluating assignee expressions)
        // but approval routing is handled by the approval service layer.
        ctx.toExpressionScope();
        return new NodeOutcome.WaitApproval();
    }
}
