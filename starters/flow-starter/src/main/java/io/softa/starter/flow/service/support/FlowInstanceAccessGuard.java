package io.softa.starter.flow.service.support;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.runtime.state.ReturnedApprovalContext;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * Authorization guard for instance-scoped approval queries.
 * <p>
 * The {@code /instance/{instanceId}} read endpoints return the full cross-actor
 * approval task list / history for an instance, so they must be restricted to
 * users involved in that instance. A caller is authorized when they are the
 * instance initiator or already appear as a participant in the returned rows;
 * everyone else is denied with {@link FlowAuthorizationException} (HTTP 403)
 * rather than the previous unscoped read open to any authenticated user.
 * <p>
 * Spring Security method-security ({@code @PreAuthorize}) is not on the
 * classpath, so an annotation-based {@code FLOW_ADMIN} route would not be
 * enforced; participant-scoping is enforced in code instead.
 */
@Component
public class FlowInstanceAccessGuard {

    private final FlowInstanceService instanceService;

    private final ApprovalAuditReader auditReader;

    public FlowInstanceAccessGuard(FlowInstanceService instanceService, ApprovalAuditReader auditReader) {
        this.instanceService = instanceService;
        this.auditReader = auditReader;
    }

    /**
     * Authorizes a caller to view an instance's cross-actor approval data.
     *
     * @param instanceId          the runtime instance id being queried
     * @param requesterId         the authenticated caller's user id
     * @param participantInResult whether the caller already appears as an actor in the loaded rows
     * @throws FlowAuthorizationException if the caller is neither a participant nor the initiator
     */
    public void requireInstanceViewer(String instanceId, String requesterId, boolean participantInResult) {
        if (!StringUtils.hasText(requesterId)) {
            throw new FlowAuthorizationException("Authentication is required to view instance " + instanceId);
        }
        if (participantInResult || isInitiator(instanceId, requesterId)) {
            return;
        }
        throw new FlowAuthorizationException(
                "User is not a participant of flow instance " + instanceId);
    }

    /**
     * Authorizes a caller against the full persisted runtime state before detail endpoints expose it.
     */
    public void requireInstanceViewer(FlowExecutionState state, String requesterId) {
        String instanceId = state == null ? null : state.getInstanceId();
        if (!StringUtils.hasText(requesterId)) {
            throw new FlowAuthorizationException("Authentication is required to view instance " + instanceId);
        }
        if (state != null
                && (requesterId.equals(state.getInitiatorId())
                || isParticipant(state, requesterId)
                || hasInitiatorFallback(state, requesterId))) {
            return;
        }
        throw new FlowAuthorizationException(
                "User is not a participant of flow instance " + instanceId);
    }

    private boolean hasInitiatorFallback(FlowExecutionState state, String requesterId) {
        return StringUtils.hasText(state.getInstanceId()) && isInitiator(state.getInstanceId(), requesterId);
    }

    private boolean isInitiator(String instanceId, String requesterId) {
        return instanceService.findByInstanceId(instanceId)
                .map(FlowInstance::getInitiatorId)
                .map(requesterId::equals)
                .orElse(false);
    }

    private boolean isParticipant(FlowExecutionState state, String requesterId) {
        return pendingApprovalParticipant(state.getPendingApprovals(), requesterId)
                || returnedApprovalParticipant(state.getReturnedApproval(), requesterId)
                || auditHistoryParticipant(auditReader.fullHistory(state), requesterId);
    }

    private static boolean pendingApprovalParticipant(List<PendingApproval> approvals, String requesterId) {
        if (approvals == null) {
            return false;
        }
        return approvals.stream().anyMatch(approval -> pendingApprovalParticipant(approval, requesterId));
    }

    private static boolean pendingApprovalParticipant(PendingApproval approval, String requesterId) {
        return approval != null
                && (contains(approval.getApprovers(), requesterId)
                || contains(approval.getApprovedActors(), requesterId)
                || contains(approval.getRejectedActors(), requesterId)
                || requesterId.equals(approval.getBlockedActorId())
                || requesterId.equals(approval.getPrerequisiteActorId()));
    }

    private static boolean returnedApprovalParticipant(ReturnedApprovalContext returnedApproval, String requesterId) {
        return returnedApproval != null
                && (requesterId.equals(returnedApproval.getActorId())
                || requesterId.equals(returnedApproval.getTargetActorId())
                || pendingApprovalParticipant(returnedApproval.getPendingApproval(), requesterId));
    }

    private static boolean auditHistoryParticipant(List<ApprovalActionAuditEntry> history, String requesterId) {
        if (history == null) {
            return false;
        }
        return history.stream().anyMatch(entry -> entry != null
                && (requesterId.equals(entry.getActorId())
                || requesterId.equals(entry.getTargetActorId())
                || contains(entry.getApprovedActors(), requesterId)
                || contains(entry.getRejectedActors(), requesterId)));
    }

    private static boolean contains(List<String> actors, String requesterId) {
        return actors != null && actors.contains(requesterId);
    }
}
