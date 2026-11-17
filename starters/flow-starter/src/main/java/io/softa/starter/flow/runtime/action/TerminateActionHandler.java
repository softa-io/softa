package io.softa.starter.flow.runtime.action;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowTerminateRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.engine.FlowStatusTransitions;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.*;

/**
 * Ends an instance that runs, waits or failed, as an administrator: its pending approvals close, the instance is
 * {@code CANCELLED} — a record's approval writes its record Cancelled, a final state it is not submitted from
 * again — and the approval history records who ended it and why. The initiator is told.
 *
 * <p>A failed approval has two ways out: its submitter takes it back (the record a draft again), or an
 * administrator terminates it (the record cancelled).
 */
@Component
public class TerminateActionHandler implements FlowActionHandler<FlowTerminateRequest> {

    private static final Set<FlowExecutionStatus> TERMINABLE = EnumSet.of(
            FlowExecutionStatus.RUNNING, FlowExecutionStatus.WAITING, FlowExecutionStatus.FAILED);

    private final FlowActionContextService contextService;
    private final FlowAuditService auditService;
    private final ApprovalNotificationService notificationService;

    public TerminateActionHandler(FlowActionContextService contextService,
                                  FlowAuditService auditService,
                                  ApprovalNotificationService notificationService) {
        this.contextService = contextService;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Override
    public FlowExecutionState handle(FlowTerminateRequest request) {
        if (request == null || !StringUtils.hasText(request.getInstanceId()) || !StringUtils.hasText(request.getActorId())) {
            throw new FlowActionValidationException("instanceId and actorId are required to terminate a flow instance");
        }
        if (!StringUtils.hasText(request.getComment())) {
            throw new FlowActionValidationException("A reason is required to terminate this instance.");
        }
        FlowExecutionState state = contextService.requireState(request.getInstanceId());
        if (!TERMINABLE.contains(state.getStatus())) {
            throw new FlowActionValidationException("Only an instance that is running, waiting or failed can be "
                    + "terminated; this one is " + state.getStatus().getType() + ". Refresh to see where it stands.");
        }
        CompiledFlowDefinition definition = contextService.resolveDefinition(state.getBundleId());
        FlowExecutionStatus statusBefore = state.getStatus();

        state.getPendingApprovals().clear();
        FlowStatusTransitions.apply(state, FlowExecutionStatus.CANCELLED);
        state.getTrace().add(FlowExecutionTraceEntry.builder()
                .flowId(definition.getFlowId())
                .eventType(FlowTraceEventType.FLOW_TERMINATED)
                .eventTime(LocalDateTime.now())
                .message("Terminated by " + request.getActorId() + ": " + request.getComment())
                .build());
        auditService.appendApprovalAudit(state, ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.TERMINATE)
                .flowId(definition.getFlowId())
                .flowRevision(definition.getRevision())
                .actorId(request.getActorId())
                .comment(request.getComment())
                .statusBefore(statusBefore)
                .statusAfter(state.getStatus())
                .build());
        contextService.persistState(state);
        notificationService.notify(new FlowNotificationEvent.Terminated(state, request.getActorId(), request.getComment()));
        return state;
    }
}
