package io.softa.starter.flow.runtime.action;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowUrgeRequest;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.*;

/**
 * Handles urge (催办) actions — sends a reminder notification to all pending approvers.
 */
@Component
public class UrgeActionHandler implements FlowActionHandler<FlowUrgeRequest> {

    private final FlowActionContextService contextService;
    private final FlowAuditService auditService;
    private final ApprovalNotificationService notificationService;

    public UrgeActionHandler(FlowActionContextService contextService,
                             FlowAuditService auditService,
                             ApprovalNotificationService notificationService) {
        this.contextService = contextService;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Override
    public FlowExecutionState handle(FlowUrgeRequest request) {
        if (request == null || !StringUtils.hasText(request.getInstanceId())) {
            throw new FlowActionValidationException("instanceId is required to urge pending approvers");
        }

        FlowExecutionState state = contextService.requireState(request.getInstanceId());
        contextService.requireWaitingApproval(state, request.getInstanceId(), "urged");

        // Collect all pending approver actor IDs
        List<String> pendingActorIds = new ArrayList<>();
        for (PendingApproval pending : state.getPendingApprovals()) {
            if (pending.getApprovers() != null) {
                for (String approver : pending.getApprovers()) {
                    if (!pendingActorIds.contains(approver)
                            && (pending.getApprovedActors() == null || !pending.getApprovedActors().contains(approver))
                            && (pending.getRejectedActors() == null || !pending.getRejectedActors().contains(approver))) {
                        pendingActorIds.add(approver);
                    }
                }
            }
        }

        if (pendingActorIds.isEmpty()) {
            throw new FlowActionValidationException("No pending approvers to urge for instance: " + request.getInstanceId());
        }

        String message = StringUtils.hasText(request.getMessage())
                ? request.getMessage()
                : "Please process as soon as possible";

        // Add trace
        state.getTrace().add(FlowExecutionTraceEntry.builder()
                .flowCode(state.getFlowCode())
                .eventType(FlowTraceEventType.FLOW_URGED)
                .eventTime(LocalDateTime.now())
                .message("Urge sent by " + request.getActorId() + " to " + pendingActorIds + ": " + message)
                .build());

        // Add audit entry
        auditService.appendApprovalAudit(state, ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.URGE)
                .flowCode(state.getFlowCode())
                .flowRevision(state.getFlowRevision())
                .actorId(request.getActorId())
                .comment(message)
                .statusBefore(state.getStatus())
                .statusAfter(state.getStatus()));

        contextService.persistState(state);

        // Send notification
        notificationService.notify(new FlowNotificationEvent.Urged(state, pendingActorIds, request.getActorId(), message));

        return state;
    }
}

