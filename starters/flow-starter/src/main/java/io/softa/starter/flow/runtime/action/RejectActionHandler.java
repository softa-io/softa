package io.softa.starter.flow.runtime.action;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.engine.FlowStatusTransitions;
import io.softa.starter.flow.runtime.api.FlowRejectRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.ApprovalActorValidator;
import io.softa.starter.flow.runtime.engine.ApprovalContext;
import io.softa.starter.flow.runtime.engine.ApprovalLifecycleService;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowActionReplayGuard;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.*;

/**
 * Handles reject actions for pending approvals.
 */
@Component
public class RejectActionHandler implements FlowActionHandler<FlowRejectRequest> {

    private final FlowActionContextService contextService;
    private final ApprovalActorValidator actorValidator;
    private final ApprovalLifecycleService lifecycleService;
    private final FlowAuditService auditService;
    private final ApprovalNotificationService notificationService;
    private final FlowActionReplayGuard replayGuard;

    public RejectActionHandler(FlowActionContextService contextService,
                               ApprovalActorValidator actorValidator,
                               ApprovalLifecycleService lifecycleService,
                               FlowAuditService auditService,
                               ApprovalNotificationService notificationService,
                               FlowActionReplayGuard replayGuard) {
        this.contextService = contextService;
        this.actorValidator = actorValidator;
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.replayGuard = replayGuard;
    }

    @Override
    public FlowExecutionState handle(FlowRejectRequest request) {
        if (request == null || !StringUtils.hasText(request.getInstanceId()) || !StringUtils.hasText(request.getNodeId())) {
            throw new FlowActionValidationException("instanceId and nodeId are required to reject a pending node");
        }
        FlowExecutionState state = contextService.requireState(request.getInstanceId());
        // Idempotent replay: the actor already rejected this node this cycle → return
        // current state instead of throwing (the completing reject already removed its pending).
        if (replayGuard.isRejectReplay(state, request.getNodeId(), request.getActorId())) {
            return state;
        }
        contextService.requireWaitingApproval(state, request.getInstanceId(), "rejected");
        ApprovalContext ctx = contextService.loadApprovalContext(state, request.getNodeId());
        PendingApproval pendingApproval = ctx.pendingApproval();
        CompiledFlowDefinition definition = ctx.definition();
        CompiledFlowNode node = ctx.node();
        FlowExecutionStatus statusBefore = ctx.statusBefore();

        actorValidator.validateRejectActor(pendingApproval, request.getActorId(), node);
        lifecycleService.assertActorNotBlockedByPrerequisite(pendingApproval, request.getActorId(), node);
        if (lifecycleService.requiresTrackedReject(pendingApproval)) {
            if (!StringUtils.hasText(request.getActorId())) {
                throw new FlowActionValidationException("actorId is required to reject this approval node: " + request.getNodeId());
            }
            if (pendingApproval.getApprovedActors().contains(request.getActorId())) {
                throw new FlowActionValidationException("Approver has already approved this approval node: " + request.getActorId());
            }
            if (pendingApproval.getRejectedActors().contains(request.getActorId())) {
                throw new FlowActionValidationException("Approver has already rejected this approval node: " + request.getActorId());
            }
            pendingApproval.getRejectedActors().add(request.getActorId());
            if (pendingApproval.getRejectedActors().size() < pendingApproval.getRequiredRejectCount()) {
                auditService.addTrace(state, definition.getFlowCode(), node, FlowTraceEventType.WAIT_APPROVAL,
                        auditService.buildPartialRejectMessage(node, pendingApproval, request.getActorId()));
                auditService.appendApprovalAudit(state, auditService.baseBuilder(definition, node, pendingApproval, statusBefore, state.getStatus())
                        .action(ApprovalActionType.REJECT)
                        .actorId(request.getActorId())
                        .comment(request.getComment()));
                contextService.persistState(state);
                return state;
            }
        }

        state.getPendingApprovals().clear();
        state.setReturnedApproval(null);
        FlowStatusTransitions.apply(state, FlowExecutionStatus.REJECTED);
        contextService.mergeVariables(state, Map.of("approvalDecision", auditService.buildApprovalDecisionPayload("Rejected", request)));
        auditService.addTrace(state, definition.getFlowCode(), node, FlowTraceEventType.APPROVAL_REJECTED,
                auditService.buildRejectionMessage(node, request));
        auditService.appendApprovalAudit(state, auditService.baseBuilder(definition, node, pendingApproval, statusBefore, state.getStatus())
                .action(ApprovalActionType.REJECT)
                .actorId(request.getActorId())
                .comment(request.getComment()));
        contextService.persistState(state);
        notificationService.notify(new FlowNotificationEvent.TaskCompleted(state, pendingApproval, false));
        return state;
    }
}
