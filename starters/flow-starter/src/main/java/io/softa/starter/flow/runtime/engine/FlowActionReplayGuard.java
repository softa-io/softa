package io.softa.starter.flow.runtime.engine;

import java.util.Set;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Detects an at-least-once REPLAY of a once-only vote (approve / reject) so the engine can return
 * the current state idempotently instead of throwing or double-applying (idempotency
 * via domain state). A replay = the actor already recorded that vote on this node: an instance reaches
 * each of its approval nodes once — a return or a withdrawal ends it, and a resubmission is a new instance.
 *
 * <p>Application-level on purpose (not a blanket unique on the audit log, which would wrongly forbid
 * legitimately repeatable actions — comment / urge / cc / read). It covers the replay of both an
 * in-flight partial vote and a completing vote (whose pending was already removed) uniformly, via the
 * approval action ledger. It does not, by itself, serialize a true simultaneous
 * double-submit — that is the optimistic-lock concern (P0#5).
 */
@Component
public class FlowActionReplayGuard {

    private static final Set<ApprovalActionType> APPROVE_ACTIONS =
            Set.of(ApprovalActionType.APPROVE, ApprovalActionType.AUTO_APPROVE);
    private static final Set<ApprovalActionType> REJECT_ACTIONS =
            Set.of(ApprovalActionType.REJECT);

    private final ApprovalAuditReader auditReader;

    public FlowActionReplayGuard(ApprovalAuditReader auditReader) {
        this.auditReader = auditReader;
    }

    public boolean isApproveReplay(FlowExecutionState state, String nodeId, String actorId) {
        return alreadyActed(state, nodeId, actorId, APPROVE_ACTIONS);
    }

    public boolean isRejectReplay(FlowExecutionState state, String nodeId, String actorId) {
        return alreadyActed(state, nodeId, actorId, REJECT_ACTIONS);
    }

    private boolean alreadyActed(FlowExecutionState state, String nodeId, String actorId,
                                 Set<ApprovalActionType> actions) {
        if (state == null || nodeId == null || actorId == null) {
            return false;
        }
        return auditReader.fullHistory(state).stream().anyMatch(e ->
                actions.contains(e.getAction())
                        && nodeId.equals(e.getNodeId())
                        && actorId.equals(e.getActorId()));
    }
}
