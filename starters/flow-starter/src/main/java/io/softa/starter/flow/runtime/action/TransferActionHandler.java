package io.softa.starter.flow.runtime.action;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowTransferRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.ApprovalActorValidator;
import io.softa.starter.flow.runtime.engine.ApprovalContext;
import io.softa.starter.flow.runtime.engine.ApprovalLifecycleService;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * An approver handing their pending task to someone else — the one way an approver passes a task on. The new
 * approver must not be on the step already and must be able to act; the one handing it on must not have voted.
 * (Delegation rules route tasks as they arrive; a manual "delegate" was the same hand-over under another name and is
 * gone — its past records still read as {@link ApprovalActionType#DELEGATE}.)
 */
@Component
public class TransferActionHandler implements FlowActionHandler<FlowTransferRequest> {

    private final FlowActionContextService contextService;
    private final ApprovalActorValidator actorValidator;
    private final ApprovalLifecycleService lifecycleService;
    private final FlowAuditService auditService;
    private final ApprovalNotificationService notificationService;

    public TransferActionHandler(FlowActionContextService contextService,
                                 ApprovalActorValidator actorValidator,
                                 ApprovalLifecycleService lifecycleService,
                                 FlowAuditService auditService,
                                 ApprovalNotificationService notificationService) {
        this.contextService = contextService;
        this.actorValidator = actorValidator;
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Override
    public FlowExecutionState handle(FlowTransferRequest request) {
        if (request == null
                || !StringUtils.hasText(request.getInstanceId())
                || !StringUtils.hasText(request.getNodeId())
                || !StringUtils.hasText(request.getActorId())
                || !StringUtils.hasText(request.getTargetActorId())) {
            throw new FlowActionValidationException("instanceId, nodeId, actorId, and targetActorId are required to transfer a pending task");
        }
        if (request.getActorId().equals(request.getTargetActorId())) {
            throw new FlowActionValidationException("Transfer target must be different from the source actor");
        }
        ApprovalContext ctx = contextService.loadApprovalContext(request.getInstanceId(), request.getNodeId(),
                "transferred");
        FlowExecutionState state = ctx.state();
        PendingApproval pendingApproval = ctx.pendingApproval();
        CompiledFlowNode node = ctx.node();

        actorValidator.validateTransferActors(pendingApproval, request, node);
        lifecycleService.replaceApprover(pendingApproval, request.getActorId(), request.getTargetActorId());

        auditService.addTrace(state, ctx.definition().getFlowId(), node, FlowTraceEventType.APPROVAL_TRANSFERRED,
                auditService.buildTransferMessage(node, request));
        auditService.appendApprovalAudit(state, auditService.baseEntry(ctx.definition(), node, pendingApproval,
                        ctx.statusBefore(), state.getStatus()).toBuilder()
                .action(ApprovalActionType.TRANSFER)
                .actorId(request.getActorId())
                .targetActorId(request.getTargetActorId())
                .comment(request.getComment())
                .build());
        contextService.persistState(state);
        notificationService.notify(new FlowNotificationEvent.TaskTransferred(state, pendingApproval,
                request.getTargetActorId()));
        return state;
    }
}
