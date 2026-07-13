package io.softa.starter.flow.runtime.action;

import io.softa.starter.flow.runtime.api.AbstractFlowNodeTargetActorRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.ApprovalActorValidator;
import io.softa.starter.flow.runtime.engine.ApprovalContext;
import io.softa.starter.flow.runtime.engine.ApprovalLifecycleService;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Shared template for replace-approver actions (transfer / delegate), which differ only in
 * their per-action validation, trace event type, audit message, audit action type, and the
 * emitted notification event. Request-field validation (with action-specific messages) stays
 * in the concrete handlers; this base owns the identical state load and mutation body.
 *
 * @param <R> the concrete target-actor request type
 */
public abstract class AbstractReplaceApproverActionHandler<R extends AbstractFlowNodeTargetActorRequest>
        implements FlowActionHandler<R> {

    protected final FlowActionContextService contextService;
    protected final ApprovalActorValidator actorValidator;
    protected final ApprovalLifecycleService lifecycleService;
    protected final FlowAuditService auditService;
    protected final ApprovalNotificationService notificationService;

    protected AbstractReplaceApproverActionHandler(FlowActionContextService contextService,
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

    /**
     * Runs the shared replace-approver body after the concrete handler has validated request
     * fields. Loads the approval context (gated on WAITING via {@code waitingActionLabel}),
     * delegates to the supplied {@code validation}, replaces the approver, records the trace and
     * audit, persists, and finally emits the notification.
     */
    protected final FlowExecutionState replaceApprover(R request,
                                                       String waitingActionLabel,
                                                       ApprovalValidation<R> validation,
                                                       FlowTraceEventType traceEventType,
                                                       AuditMessage<R> auditMessage,
                                                       ApprovalActionType actionType,
                                                       NotificationFactory notification) {
        ApprovalContext ctx = contextService.loadApprovalContext(
                request.getInstanceId(), request.getNodeId(), waitingActionLabel);
        FlowExecutionState state = ctx.state();
        PendingApproval pendingApproval = ctx.pendingApproval();
        CompiledFlowDefinition definition = ctx.definition();
        CompiledFlowNode node = ctx.node();

        validation.validate(pendingApproval, request, node);
        lifecycleService.replaceApprover(pendingApproval, request.getActorId(), request.getTargetActorId());

        auditService.addTrace(state, definition.getFlowCode(), node, traceEventType,
                auditMessage.build(node, request));
        auditService.appendApprovalAudit(state, auditService.baseBuilder(definition, node, pendingApproval, ctx.statusBefore(), state.getStatus())
                .action(actionType)
                .actorId(request.getActorId())
                .targetActorId(request.getTargetActorId())
                .comment(request.getComment()));
        contextService.persistState(state);
        notificationService.notify(notification.create(state, pendingApproval));
        return state;
    }

    @FunctionalInterface
    protected interface ApprovalValidation<R> {
        void validate(PendingApproval pendingApproval, R request, CompiledFlowNode node);
    }

    @FunctionalInterface
    protected interface AuditMessage<R> {
        String build(CompiledFlowNode node, R request);
    }

    @FunctionalInterface
    protected interface NotificationFactory {
        FlowNotificationEvent create(FlowExecutionState state, PendingApproval pendingApproval);
    }
}
