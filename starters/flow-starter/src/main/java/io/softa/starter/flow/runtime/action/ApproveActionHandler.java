package io.softa.starter.flow.runtime.action;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.*;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.*;

/**
 * Handles approve actions for pending approvals.
 */
@Component
public class ApproveActionHandler implements FlowActionHandler<FlowApproveRequest> {

    private final FlowActionContextService contextService;
    private final ApprovalActorValidator actorValidator;
    private final ApprovalLifecycleService lifecycleService;
    private final FlowAuditService auditService;
    private final FlowExecutionOrchestrator orchestrator;
    private final ApprovalNotificationService notificationService;
    private final ApprovalFormWriteService formWriteService;
    private final FlowActionReplayGuard replayGuard;

    public ApproveActionHandler(FlowActionContextService contextService,
                                ApprovalActorValidator actorValidator,
                                ApprovalLifecycleService lifecycleService,
                                FlowAuditService auditService,
                                FlowExecutionOrchestrator orchestrator,
                                ApprovalNotificationService notificationService,
                                ApprovalFormWriteService formWriteService,
                                FlowActionReplayGuard replayGuard) {
        this.contextService = contextService;
        this.actorValidator = actorValidator;
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
        this.orchestrator = orchestrator;
        this.notificationService = notificationService;
        this.formWriteService = formWriteService;
        this.replayGuard = replayGuard;
    }

    @Override
    public FlowExecutionState handle(FlowApproveRequest request) {
        if (request == null || !StringUtils.hasText(request.getInstanceId()) || !StringUtils.hasText(request.getNodeId())) {
            throw new FlowActionValidationException("instanceId and nodeId are required to approve a pending node");
        }
        FlowExecutionState state = contextService.requireState(request.getInstanceId());
        // Idempotent replay of a vote already cast this cycle → return current state,
        // rather than throwing or double-applying. Must run before the pending-node lookup, since a
        // completing approve already removed its pending.
        if (replayGuard.isApproveReplay(state, request.getNodeId(), request.getActorId())) {
            return state;
        }
        contextService.requireWaitingApproval(state, request.getInstanceId(), "approved");
        ApprovalContext ctx = contextService.loadApprovalContext(state, request.getNodeId());
        PendingApproval pendingApproval = ctx.pendingApproval();
        CompiledFlowDefinition definition = ctx.definition();
        CompiledFlowNode node = ctx.node();
        FlowExecutionStatus statusBefore = ctx.statusBefore();

        actorValidator.validateApprovalActor(pendingApproval, request.getActorId(), node);
        lifecycleService.assertActorNotBlockedByPrerequisite(pendingApproval, request.getActorId(), node);
        // Enforce form field permissions + write sanitized edits to the business row.
        formWriteService.applyFormData(state, node, request.getFormData());
        if (lifecycleService.requiresTrackedApprover(pendingApproval)) {
            if (!StringUtils.hasText(request.getActorId())) {
                throw new FlowActionValidationException("actorId is required to approve this approval node: " + request.getNodeId());
            }
            if (pendingApproval.getRejectedActors().contains(request.getActorId())) {
                throw new FlowActionValidationException("Approver has already rejected this approval node: " + request.getActorId());
            }
            if (pendingApproval.getApprovedActors().contains(request.getActorId())) {
                throw new FlowActionValidationException("Approver has already approved this approval node: " + request.getActorId());
            }
            pendingApproval.getApprovedActors().add(request.getActorId());
            if (!lifecycleService.canCompleteApproval(pendingApproval)) {
                auditService.addTrace(state, definition.getFlowCode(), node, FlowTraceEventType.WAIT_APPROVAL,
                        auditService.buildPartialApprovalMessage(node, pendingApproval, request.getActorId()));
                auditService.appendApprovalAudit(state, auditService.baseBuilder(definition, node, pendingApproval, statusBefore, state.getStatus())
                        .action(ApprovalActionType.APPROVE)
                        .actorId(request.getActorId())
                        .comment(request.getComment()));
                contextService.persistState(state);
                return state;
            }
        }

        // Record the completing approval BEFORE advancing, so a downstream node's approver-dedup
        // (审批人去重) sees that this actor approved this node during the same resume cascade.
        auditService.appendApprovalAudit(state, auditService.baseBuilder(definition, node, pendingApproval, statusBefore, FlowExecutionStatus.RUNNING)
                .action(ApprovalActionType.APPROVE)
                .actorId(request.getActorId())
                .comment(request.getComment()));
        orchestrator.resumeApprovedNode(state, pendingApproval, definition, node);
        contextService.persistState(state);
        notificationService.notify(new FlowNotificationEvent.TaskCompleted(state, pendingApproval, true));
        return state;
    }
}
