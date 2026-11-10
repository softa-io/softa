package io.softa.starter.flow.runtime.action;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.starter.flow.runtime.api.FlowReassignRequest;
import io.softa.starter.flow.runtime.engine.ApprovalActorValidator;
import io.softa.starter.flow.runtime.engine.ApprovalContext;
import io.softa.starter.flow.runtime.engine.ApprovalLifecycleService;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.engine.FlowExecutionOrchestrator;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * An administrator moving an approver's pending task to someone else — a transfer the approver did not make.
 * The history records it as the approver's transfer, with the administrator as its operator and their reason;
 * a new approver already on the step merges with the old one's place, and one who cannot act is refused. The new
 * approver is given the task and the initiator is told who now holds it.
 *
 * <p>Merged into an approver who already approved, the step may need nobody else: the place merged away was the
 * last one still to approve — an all-approve step whose other approvers all approved, say. The step then passes
 * there and then, as the approval that met its threshold would have passed it, and the instance goes on; the new
 * approver has no task to be given.
 *
 * <p>Only the step the instance waits on now moves: a later step resolves its approvers when it is reached.
 */
@Component
public class ReassignActionHandler implements FlowActionHandler<FlowReassignRequest> {

    private final FlowActionContextService contextService;
    private final ApprovalActorValidator actorValidator;
    private final ApprovalLifecycleService lifecycleService;
    private final FlowAuditService auditService;
    private final ApprovalNotificationService notificationService;
    private final FlowExecutionOrchestrator orchestrator;

    public ReassignActionHandler(FlowActionContextService contextService,
                                 ApprovalActorValidator actorValidator,
                                 ApprovalLifecycleService lifecycleService,
                                 FlowAuditService auditService,
                                 ApprovalNotificationService notificationService,
                                 FlowExecutionOrchestrator orchestrator) {
        this.contextService = contextService;
        this.actorValidator = actorValidator;
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.orchestrator = orchestrator;
    }

    @Override
    public FlowExecutionState handle(FlowReassignRequest request) {
        if (request == null || !StringUtils.hasText(request.getInstanceId()) || !StringUtils.hasText(request.getNodeId())
                || !StringUtils.hasText(request.getActorId()) || !StringUtils.hasText(request.getTargetActorId())
                || !StringUtils.hasText(request.getOperatorId())) {
            throw new FlowActionValidationException("instanceId, nodeId, actorId, targetActorId and operatorId are "
                    + "required to reassign a pending task");
        }
        if (request.getActorId().equals(request.getTargetActorId())) {
            throw new FlowActionValidationException("The new approver must be someone else");
        }
        if (!StringUtils.hasText(request.getComment())) {
            throw new FlowActionValidationException("A reason is required to reassign a pending task");
        }
        ApprovalContext ctx = contextService.loadApprovalContext(request.getInstanceId(), request.getNodeId(), "reassigned");
        FlowExecutionState state = ctx.state();
        PendingApproval pendingApproval = ctx.pendingApproval();

        actorValidator.validateReassignActor(pendingApproval, request.getActorId(), request.getTargetActorId(), ctx.node());
        lifecycleService.mergeApprover(pendingApproval, request.getActorId(), request.getTargetActorId());

        auditService.addTrace(state, ctx.definition().getFlowId(), ctx.node(), FlowTraceEventType.APPROVAL_TRANSFERRED,
                "Reassigned by " + request.getOperatorId() + " from " + request.getActorId() + " to "
                        + request.getTargetActorId() + ": " + request.getComment());
        auditService.appendApprovalAudit(state, auditService.baseEntry(ctx.definition(), ctx.node(), pendingApproval,
                        ctx.statusBefore(), state.getStatus()).toBuilder()
                .action(ApprovalActionType.TRANSFER)
                .actorId(request.getActorId())
                .targetActorId(request.getTargetActorId())
                .operatorId(request.getOperatorId())
                .comment(request.getComment())
                .build());
        if (lifecycleService.requiresTrackedApprover(pendingApproval)
                && lifecycleService.canCompleteApproval(pendingApproval)) {
            // The steps after it run as after any approval: past the administrator's own permissions — the flow's
            // writes, not theirs — and failing without undoing the step's approval.
            FlowExecutionState resumed = ContextUtils.withoutPermissionChecks(
                    () -> orchestrator.resumeApprovedNode(state, pendingApproval, ctx.definition(), ctx.node()));
            contextService.persistState(resumed);
            notificationService.notify(new FlowNotificationEvent.TaskCompleted(resumed, pendingApproval, true));
            notificationService.notify(new FlowNotificationEvent.Reassigned(resumed, pendingApproval,
                    request.getActorId(), request.getTargetActorId(), request.getComment()));
            return resumed;
        }
        contextService.persistState(state);
        notificationService.notify(new FlowNotificationEvent.TaskTransferred(state, pendingApproval, request.getTargetActorId()));
        notificationService.notify(new FlowNotificationEvent.Reassigned(state, pendingApproval, request.getActorId(),
                request.getTargetActorId(), request.getComment()));
        return state;
    }
}
