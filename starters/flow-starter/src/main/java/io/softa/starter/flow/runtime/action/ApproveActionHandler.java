package io.softa.starter.flow.runtime.action;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
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
        actorValidator.requireNotBlockedByPrerequisite(pendingApproval, request.getActorId(), node);
        // The actor is this node's approver — that, not the actor's own roles, is what authorizes the
        // writes from here on: the form edits the node allows and every node the approval resumes.
        // An approver rarely holds update on the record they approve; approving is the grant. The
        // check above is the entry check; the rest runs with the actor's row scope set aside.
        return asApprover(() -> approveValidated(request, state, pendingApproval, definition, node, statusBefore));
    }

    private FlowExecutionState approveValidated(FlowApproveRequest request, FlowExecutionState state,
                                                PendingApproval pendingApproval, CompiledFlowDefinition definition,
                                                CompiledFlowNode node, FlowExecutionStatus statusBefore) {
        // Enforce form field permissions + write sanitized edits to the business row.
        formWriteService.applyFormData(state, node, request.getFormData());
        if (lifecycleService.requiresTrackedApprover(pendingApproval)) {
            if (!StringUtils.hasText(request.getActorId())) {
                throw new FlowActionValidationException("actorId is required to approve this approval node: " + request.getNodeId());
            }
            actorValidator.requireNotVoted(pendingApproval, request.getActorId(), node);
            pendingApproval.getApprovedActors().add(request.getActorId());
            if (!lifecycleService.canCompleteApproval(pendingApproval)) {
                auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.WAIT_APPROVAL,
                        auditService.buildPartialApprovalMessage(node, pendingApproval, request.getActorId()));
                auditService.appendApprovalAudit(state, auditService.baseEntry(definition, node, pendingApproval, statusBefore, state.getStatus()).toBuilder()
                        .action(ApprovalActionType.APPROVE)
                        .actorId(request.getActorId())
                        .comment(request.getComment())
                        .build());
                contextService.persistState(state);
                return state;
            }
        }

        // Record the completing approval BEFORE advancing, so a downstream node's approver-dedup
        // (审批人去重) sees that this actor approved this node during the same resume cascade.
        auditService.appendApprovalAudit(state, auditService.baseEntry(definition, node, pendingApproval, statusBefore, FlowExecutionStatus.RUNNING).toBuilder()
                .action(ApprovalActionType.APPROVE)
                .actorId(request.getActorId())
                .comment(request.getComment())
                .build());
        // The steps after the approval may fail without undoing it: the instance then comes back failed.
        FlowExecutionState resumed = orchestrator.resumeApprovedNode(state, pendingApproval, definition, node);
        contextService.persistState(resumed);
        notificationService.notify(new FlowNotificationEvent.TaskCompleted(resumed, pendingApproval, true));
        return resumed;
    }

    /**
     * Run the validated approval with the actor's own permission checks switched off.
     *
     * <p>Every check, not only row scope: the form edits and the writes of every node the approval
     * resumes skip sensitive-field write checks as well. The node's form configuration is therefore the
     * only authorization for the fields the approver edits — an approver whose roles hold no sensitive
     * field set covering a field can still write it when the node's form exposes it as editable. Flow
     * designers must not make a sensitive field editable on an approval form unless every approver of
     * that node is meant to edit it.
     */
    private static FlowExecutionState asApprover(java.util.function.Supplier<FlowExecutionState> work) {
        return ContextUtils.withoutPermissionChecks(work);
    }
}
