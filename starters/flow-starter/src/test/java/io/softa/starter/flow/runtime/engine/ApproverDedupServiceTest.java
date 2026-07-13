package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.enums.ApproverDedupStrategy;
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApproverDedupService}: the GLOBAL / CONTIGUOUS distinction and the
 * resubmission-cycle guard (a prior approval before the last RETURN/RESUBMIT is discounted).
 */
class ApproverDedupServiceTest {

    private final ApprovalAuditReader auditReader =
            new ApprovalAuditReader(new NoopApprovalActionLedger());
    private final ApproverDedupService service = new ApproverDedupService(
            new ApprovalLifecycleService(new PendingApprovalFactory(new ApproverResolutionService(), auditReader)),
            new FlowAuditService(auditReader), auditReader);

    private static final String X = "x";
    private static final String Y = "y";

    private static CompiledFlowDefinition def(ApproverDedupStrategy strategy) {
        return CompiledFlowDefinition.builder().flowCode("f").approverDedup(strategy).build();
    }

    private static CompiledFlowNode node(String id) {
        CompiledFlowNode node = mock(CompiledFlowNode.class);
        when(node.getNodeId()).thenReturn(id);
        return node;
    }

    private static PendingApproval pendingFor(String approver, String nodeId) {
        return PendingApproval.builder()
                .nodeId(nodeId)
                .approvers(new ArrayList<>(List.of(approver)))
                .approvedActors(new ArrayList<>())
                .rejectedActors(new ArrayList<>())
                .requiredApprovalCount(1)
                .build();
    }

    private static ApprovalActionAuditEntry entry(ApprovalActionType action, String nodeId, String actor) {
        return ApprovalActionAuditEntry.builder().action(action).nodeId(nodeId).actorId(actor).build();
    }

    private static FlowExecutionState stateWith(ApprovalActionAuditEntry... history) {
        FlowExecutionState state = new FlowExecutionState();
        state.setStatus(FlowExecutionStatus.RUNNING);
        state.setApprovalAuditDelta(new ArrayList<>(List.of(history)));
        return state;
    }

    @Test
    void globalAutoApprovesAPriorApproverOnALaterNode() {
        FlowExecutionState state = stateWith(entry(ApprovalActionType.APPROVE, "ap1", X));
        PendingApproval pending = pendingFor(X, "ap2");

        boolean complete = service.applyAndCheckComplete(def(ApproverDedupStrategy.GLOBAL), node("ap2"), state, pending);

        assertTrue(pending.getApprovedActors().contains(X), "GLOBAL should auto-approve X (already approved ap1)");
        assertTrue(complete, "single-approver node is complete once X is auto-approved");
    }

    @Test
    void resubmitCycleGuardDiscountsApprovalsBeforeTheResubmit() {
        FlowExecutionState state = stateWith(
                entry(ApprovalActionType.APPROVE, "ap1", X),
                entry(ApprovalActionType.RESUBMIT, "ap1", X)); // document changed -> prior approval void
        PendingApproval pending = pendingFor(X, "ap2");

        boolean complete = service.applyAndCheckComplete(def(ApproverDedupStrategy.GLOBAL), node("ap2"), state, pending);

        assertFalse(pending.getApprovedActors().contains(X), "approval before the resubmit must be discounted");
        assertFalse(complete, "X must approve ap2 again after a resubmit");
    }

    @Test
    void contiguousIgnoresANonContiguousPriorApproval() {
        FlowExecutionState state = stateWith(
                entry(ApprovalActionType.APPROVE, "ap1", X),
                entry(ApprovalActionType.APPROVE, "apMid", Y)); // the node right before ap3 is Y's
        PendingApproval pending = pendingFor(X, "ap3");

        boolean complete = service.applyAndCheckComplete(def(ApproverDedupStrategy.CONTIGUOUS), node("ap3"), state, pending);

        assertFalse(pending.getApprovedActors().contains(X), "CONTIGUOUS: X did not approve the immediately preceding node");
        assertFalse(complete);
    }

    @Test
    void noneNeverAutoApproves() {
        FlowExecutionState state = stateWith(entry(ApprovalActionType.APPROVE, "ap1", X));
        PendingApproval pending = pendingFor(X, "ap2");

        boolean complete = service.applyAndCheckComplete(def(ApproverDedupStrategy.NONE), node("ap2"), state, pending);

        assertFalse(pending.getApprovedActors().contains(X));
        assertFalse(complete);
    }
}
