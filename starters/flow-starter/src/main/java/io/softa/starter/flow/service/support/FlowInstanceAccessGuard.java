package io.softa.starter.flow.service.support;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.SystemRole;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.store.FlowInstanceStore;
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
 * Monitor-admin bypass: a caller holding the system admin role may view any
 * instance — the cross-initiator monitoring console pairs with
 * {@code POST /flow/monitor/instances/search}. The role codes are read from the
 * framework-layer {@link Context#getRoleCodes()} (bridged onto the Context by the
 * permission-starter interceptor) exactly like the {@code @RequireRole} aspect
 * does, and the check fails closed when the consuming application populated no
 * role provider.
 */
@Component
public class FlowInstanceAccessGuard {

    private final FlowInstanceService instanceService;

    private final ApprovalAuditReader auditReader;

    private final FlowInstanceStore instanceStore;

    public FlowInstanceAccessGuard(FlowInstanceService instanceService, ApprovalAuditReader auditReader,
                                   FlowInstanceStore instanceStore) {
        this.instanceService = instanceService;
        this.auditReader = auditReader;
        this.instanceStore = instanceStore;
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
        if (isMonitorAdmin() || participantInResult) {
            return;
        }
        // The loaded rows can only vouch for actors who already appear in them, and that is not
        // every legitimate viewer: a pending approver who has not acted yet has no approval-record
        // row, so a records-derived participantInResult is false for exactly the person whose task
        // sits in the inbox. Fall back to the full runtime state, whose pending approvals carry the
        // not-yet-acted actors — the same participant test the state-based overload applies.
        FlowExecutionState state = instanceStore.get(instanceId).orElse(null);
        if (state != null
                && (requesterId.equals(state.getInitiatorId()) || isParticipant(state, requesterId))) {
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
        if (isMonitorAdmin()) {
            return;
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

    /**
     * Whether the current caller holds the system admin role — the monitor-console
     * bypass. Mirrors the {@code @RequireRole} aspect's read of the framework-layer
     * Context; an absent context, permission info, or role set denies (fail-closed).
     */
    private static boolean isMonitorAdmin() {
        Context context = ContextHolder.getContext();
        Set<String> roleCodes = context == null ? null : context.getRoleCodes();
        return roleCodes != null && roleCodes.contains(SystemRole.SYSTEM_ROLE_ADMIN.getCode());
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
