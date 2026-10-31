package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.ApproverDedupStrategy;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.SelfApprovalStrategy;
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApproverDedupService}: the GLOBAL / CONTIGUOUS distinction, and the record's own
 * people — its submitter and the employee it is about — approved for automatically on an Approval flow
 * unless the flow asks them like anyone else (recorded, and counted by the step's approval mode).
 */
class ApproverDedupServiceTest {

    private final ApprovalAuditReader auditReader =
            new ApprovalAuditReader(new NoopApprovalActionLedger());
    private final ApproverDedupService service = new ApproverDedupService(
            new ApprovalLifecycleService(new PendingApprovalFactory(new ApproverResolutionService(), auditReader)),
            new FlowAuditService(auditReader), auditReader);

    private static final String X = "x";
    private static final String Y = "y";
    private static final String Y_USER = "2001";

    private static CompiledFlowDefinition def(ApproverDedupStrategy strategy) {
        return CompiledFlowDefinition.builder().flowId("f").approverDedup(strategy).build();
    }

    /** An Approval flow — a record's approval — with approver dedup switched off. */
    private static CompiledFlowDefinition submitFlow() {
        return submitFlow(null);
    }

    private static CompiledFlowDefinition submitFlow(SelfApprovalStrategy selfApproval) {
        return CompiledFlowDefinition.builder().flowId("f").approverDedup(ApproverDedupStrategy.NONE)
                .selfApproval(selfApproval)
                .scenario(FlowScenario.APPROVAL)
                .modelName("LeaveRequest").trigger(new SubmitTrigger()).build();
    }

    /** A record about employee 5001, whose login account is user Y, submitted by user X (HR). */
    private FlowExecutionState filedByHrFor5001() {
        OrganizationService organization = mock(OrganizationService.class);
        when(organization.getUserIdOfEmployee(5001L)).thenReturn(Long.valueOf(Y_USER));
        service.setOrganizationService(organization);
        FlowExecutionState state = stateWith();
        state.setInitiatorId(X);
        Map<String, Object> variables = new HashMap<>();
        variables.put(ApproverResolutionService.SUBJECT_ID_KEY, 5001L);
        state.setVariables(variables);
        return state;
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
    void theSubmittersOwnStepIsApprovedOnRecord() {
        FlowExecutionState state = stateWith();
        state.setInitiatorId(X);
        PendingApproval pending = pendingFor(X, "ap1");

        boolean complete = service.applyAndCheckComplete(submitFlow(), node("ap1"), state, pending);

        assertTrue(complete, "a step whose only approver is the submitter passes");
        ApprovalActionAuditEntry approval = state.getApprovalAuditDelta().getLast();
        assertEquals(ApprovalActionType.AUTO_APPROVE, approval.getAction());
        assertEquals(X, approval.getActorId());
        assertEquals("Auto-approved (submitter)", approval.getComment());
    }

    @Test
    void anAnyOneStepPassesOnTheSubmittersApproval() {
        FlowExecutionState state = stateWith();
        state.setInitiatorId(X);
        PendingApproval pending = pendingFor(X, "ap1");
        pending.getApprovers().add(Y);

        assertTrue(service.applyAndCheckComplete(submitFlow(), node("ap1"), state, pending));
        assertEquals(List.of(X), pending.getApprovedActors());
    }

    @Test
    void anAllApproveStepStillWaitsForTheOthers() {
        FlowExecutionState state = stateWith();
        state.setInitiatorId(X);
        PendingApproval pending = pendingFor(X, "ap1");
        pending.getApprovers().add(Y);
        pending.setRequiredApprovalCount(2);

        assertFalse(service.applyAndCheckComplete(submitFlow(), node("ap1"), state, pending));
        assertEquals(List.of(X), pending.getApprovedActors(), "the submitter counts; Y still has to approve");
    }

    @Test
    void onAFlowThatApprovesNoRecordTheInitiatorIsAskedLikeAnyApprover() {
        FlowExecutionState state = stateWith();
        state.setInitiatorId(X);
        PendingApproval pending = pendingFor(X, "ap1");

        assertFalse(service.applyAndCheckComplete(def(ApproverDedupStrategy.NONE), node("ap1"), state, pending));
        assertTrue(pending.getApprovedActors().isEmpty());
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

    @Test
    void theEmployeeTheRecordIsAboutIsApprovedForToo() {
        FlowExecutionState state = filedByHrFor5001();
        PendingApproval pending = pendingFor(Y_USER, "ap1");

        assertTrue(service.applyAndCheckComplete(submitFlow(), node("ap1"), state, pending));
        ApprovalActionAuditEntry approval = state.getApprovalAuditDelta().getLast();
        assertEquals(Y_USER, approval.getActorId());
        assertEquals("Auto-approved (subject)", approval.getComment());
    }

    @Test
    void aFlowThatRequiresApprovalAsksTheRecordsOwnPeopleLikeAnyone() {
        FlowExecutionState state = filedByHrFor5001();
        PendingApproval pending = pendingFor(X, "ap1");
        pending.getApprovers().add(Y_USER);

        assertFalse(service.applyAndCheckComplete(submitFlow(SelfApprovalStrategy.REQUIRE_APPROVAL), node("ap1"),
                state, pending));
        assertTrue(pending.getApprovedActors().isEmpty());
        assertTrue(service.selfApprovers(submitFlow(SelfApprovalStrategy.REQUIRE_APPROVAL), state).isEmpty());
    }

    @Test
    void theSubmittersRoleComesFirstWhenTheyFiledForThemselves() {
        FlowExecutionState state = filedByHrFor5001();
        state.setInitiatorId(Y_USER);

        assertEquals(Map.of(Y_USER, ApproverDedupService.SelfRole.SUBMITTER), service.selfApprovers(submitFlow(), state));
    }
}
