package io.softa.starter.flow.runtime.action;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowBatchCcRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.ApprovalActorValidator;
import io.softa.starter.flow.runtime.engine.ApprovalContext;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Handles batch CC actions for pending approvals.
 */
@Component
public class BatchCcActionHandler implements FlowActionHandler<FlowBatchCcRequest> {

    private final FlowActionContextService contextService;
    private final ApprovalActorValidator actorValidator;
    private final FlowAuditService auditService;

    public BatchCcActionHandler(FlowActionContextService contextService,
                                ApprovalActorValidator actorValidator,
                                FlowAuditService auditService) {
        this.contextService = contextService;
        this.actorValidator = actorValidator;
        this.auditService = auditService;
    }

    @Override
    public FlowExecutionState handle(FlowBatchCcRequest request) {
        if (request == null
                || !StringUtils.hasText(request.getInstanceId())
                || !StringUtils.hasText(request.getNodeId())
                || !StringUtils.hasText(request.getActorId())) {
            throw new FlowActionValidationException("instanceId, nodeId, and actorId are required to batch CC pending tasks");
        }

        List<String> recipients = actorValidator.normalizeCcRecipients(request.getTargetActorIds());
        if (recipients.isEmpty()) {
            throw new FlowActionValidationException("At least one non-blank targetActorId is required to batch CC pending tasks");
        }

        ApprovalContext ctx = contextService.loadApprovalContext(request.getInstanceId(), request.getNodeId(), "CCed");
        FlowExecutionState state = ctx.state();
        PendingApproval pendingApproval = ctx.pendingApproval();
        CompiledFlowDefinition definition = ctx.definition();
        CompiledFlowNode node = ctx.node();
        FlowExecutionStatus statusBefore = ctx.statusBefore();

        for (String recipient : recipients) {
            actorValidator.validateCcRequest(state, pendingApproval, actorValidator.buildCcRequest(request, recipient), node);
        }
        for (String recipient : recipients) {
            auditService.appendCcAudit(state, pendingApproval, definition, node,
                    request.getActorId(), recipient, request.getComment(), statusBefore);
        }
        contextService.persistState(state);
        return state;
    }
}
