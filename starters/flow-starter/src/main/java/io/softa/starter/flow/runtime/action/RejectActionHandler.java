package io.softa.starter.flow.runtime.action;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.runtime.engine.FlowStatusTransitions;
import io.softa.starter.flow.runtime.api.FlowRejectRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.ApprovalActorValidator;
import io.softa.starter.flow.runtime.engine.ApprovalContext;
import io.softa.starter.flow.runtime.engine.AutoCcService;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowActionReplayGuard;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.*;

/**
 * Handles reject actions for pending approvals: one approver's rejection rejects the approval — a step has no reject
 * threshold — and carries a comment saying why.
 */
@Component
public class RejectActionHandler implements FlowActionHandler<FlowRejectRequest> {

    private final FlowActionContextService contextService;
    private final ApprovalActorValidator actorValidator;
    private final FlowAuditService auditService;
    private final ApprovalNotificationService notificationService;
    private final FlowActionReplayGuard replayGuard;
    private final AutoCcService autoCcService;

    public RejectActionHandler(FlowActionContextService contextService,
                               ApprovalActorValidator actorValidator,
                               FlowAuditService auditService,
                               ApprovalNotificationService notificationService,
                               FlowActionReplayGuard replayGuard,
                               AutoCcService autoCcService) {
        this.contextService = contextService;
        this.actorValidator = actorValidator;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.replayGuard = replayGuard;
        this.autoCcService = autoCcService;
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
        // A rejection says why: the submitter is told, and the record's approval history keeps it.
        if (!StringUtils.hasText(request.getComment())) {
            throw new FlowActionValidationException("A comment is required to reject approval node: " + request.getNodeId());
        }
        // One approver's rejection rejects the approval — a rejection has no threshold to count towards — unless they
        // already approved the step: they have had their say on it.
        if (StringUtils.hasText(request.getActorId())) {
            actorValidator.requireNotVoted(pendingApproval, request.getActorId(), node);
        }

        state.getPendingApprovals().clear();
        FlowStatusTransitions.apply(state, FlowExecutionStatus.REJECTED);
        contextService.mergeVariables(state, Map.of("approvalDecision", auditService.buildApprovalDecisionPayload("Rejected", request)));
        auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.APPROVAL_REJECTED,
                auditService.buildRejectionMessage(node, request));
        auditService.appendApprovalAudit(state, auditService.baseEntry(definition, node, pendingApproval, statusBefore, state.getStatus()).toBuilder()
                .action(ApprovalActionType.REJECT)
                .actorId(request.getActorId())
                .comment(request.getComment())
                .build());
        // The flow's rejection CCs, recorded on the step that rejected it.
        autoCcService.processCc(state, node, CcTiming.ON_REJECTED);
        contextService.persistState(state);
        notificationService.notify(new FlowNotificationEvent.TaskCompleted(state, pendingApproval, false));
        return state;
    }
}
