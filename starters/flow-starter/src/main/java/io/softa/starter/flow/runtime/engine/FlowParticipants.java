package io.softa.starter.flow.runtime.engine;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Who takes part in a flow instance, as far as its state and approval history tell: its initiator and the people
 * it gives or gave a task to — its approvers, pending or done, and the actors and targets of its approval actions
 * (transfers, delegations, CCs). Participants see the instance without any configured permission, and only they
 * comment on it.
 *
 * <p>An administrator's actions make nobody a participant. The actor of an {@code URGE} or a {@code TERMINATE}
 * entry is not counted for it — an initiator who urges their own approval counts as its initiator — though anyone
 * else such an entry names still is. An administrator's reassignment is recorded as the approver's own transfer,
 * with the administrator as its operator, who is never counted.
 *
 * <p>The state and the history do not name everyone the instance gave a task to: a pending approver who never acted
 * leaves no trace in them once their step closes without them — a termination clears it, the others' votes carry
 * it. Their task, kept when it closes, still names them. {@code FlowInstanceAccessGuard.isParticipant} asks the
 * instance's tasks when this class names nobody, and is the rule to ask; this is the half of it that needs no query.
 */
public final class FlowParticipants {

    /** What an administrator may do to an instance they take no part in: doing it makes them no participant. */
    private static final Set<ApprovalActionType> ADMINISTRATIVE_ACTIONS =
            EnumSet.of(ApprovalActionType.URGE, ApprovalActionType.TERMINATE);

    private FlowParticipants() {
    }

    /**
     * Whether the user takes part in the instance, as its state and history tell.
     *
     * @param state   the instance
     * @param history its full approval history, as {@link ApprovalAuditReader#fullHistory} reads it
     * @param userId  the user
     */
    public static boolean isParticipant(FlowExecutionState state, List<ApprovalActionAuditEntry> history,
                                        String userId) {
        if (state == null || userId == null) {
            return false;
        }
        return userId.equals(state.getInitiatorId())
                || pendingApprovalParticipant(state.getPendingApprovals(), userId)
                || auditHistoryParticipant(history, userId);
    }

    /**
     * Whether the actor of an approval action takes part in the instance by it: by every action but an urge and a
     * termination, which an administrator may make on an instance they take no part in.
     */
    public static boolean actorTakesPart(ApprovalActionType action) {
        return !ADMINISTRATIVE_ACTIONS.contains(action);
    }

    private static boolean pendingApprovalParticipant(List<PendingApproval> approvals, String userId) {
        if (approvals == null) {
            return false;
        }
        return approvals.stream().anyMatch(approval -> approval != null
                && (contains(approval.getApprovers(), userId)
                || contains(approval.getApprovedActors(), userId)));
    }

    private static boolean auditHistoryParticipant(List<ApprovalActionAuditEntry> history, String userId) {
        if (history == null) {
            return false;
        }
        return history.stream().anyMatch(entry -> entry != null
                && ((actorTakesPart(entry.getAction()) && userId.equals(entry.getActorId()))
                || userId.equals(entry.getTargetActorId())
                || contains(entry.getApprovedActors(), userId)));
    }

    private static boolean contains(List<String> actors, String userId) {
        return actors != null && actors.contains(userId);
    }
}
