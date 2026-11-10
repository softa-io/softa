package io.softa.starter.flow.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowCommentRequest;
import io.softa.starter.flow.runtime.api.FlowReassignRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.api.FlowTerminateRequest;
import io.softa.starter.flow.runtime.api.FlowUrgeRequest;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.engine.FlowParticipants;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * What an administrator does to an approval from the monitoring console: terminate it — the instance cancelled,
 * its history saying who and why — and move an approver's pending task to someone else. None of it makes the
 * administrator a participant, while the approver a termination cleared stays one by the task they were given.
 */
class AdministrationScenarioTest {

    private static final String FLOW_ID = "9300";
    private static final String INITIATOR = "initiator-1";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String ADMIN = "hr-admin";

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    /** Who holds a task of which instance, as the task rows the engine's projection writes keep it. */
    private final Map<String, Set<String>> taskHolders = new HashMap<>();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.createWithTasks(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler,
            taskHolders);

    private FlowExecutionState start(List<String> approvers, String approvalMode) {
        publishService.publish(FLOW_ID, flow(approvers, approvalMode));
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
        start.setInitiatorId(INITIATOR);
        return engine.start(start);
    }

    private static FlowTerminateRequest terminate(String instanceId, String reason) {
        FlowTerminateRequest request = new FlowTerminateRequest();
        request.setInstanceId(instanceId);
        request.setActorId(ADMIN);
        request.setComment(reason);
        return request;
    }

    private static FlowUrgeRequest urge(String instanceId) {
        FlowUrgeRequest request = new FlowUrgeRequest();
        request.setInstanceId(instanceId);
        request.setActorId(ADMIN);
        return request;
    }

    private static FlowCommentRequest comment(String instanceId, String actorId) {
        FlowCommentRequest request = new FlowCommentRequest();
        request.setInstanceId(instanceId);
        request.setActorId(actorId);
        request.setComment("Is the employee right?");
        return request;
    }

    private static FlowReassignRequest reassign(String instanceId, String from, String to) {
        FlowReassignRequest request = new FlowReassignRequest();
        request.setInstanceId(instanceId);
        request.setNodeId("approval");
        request.setActorId(from);
        request.setTargetActorId(to);
        request.setOperatorId(ADMIN);
        request.setComment("Alice is on leave");
        return request;
    }

    @Test
    void aTerminatedApprovalIsCancelledAndSaysWhoEndedItAndWhy() {
        FlowExecutionState started = start(List.of(ALICE), "AnyOne");

        FlowExecutionState state = engine.terminate(terminate(started.getInstanceId(), "Filed for the wrong employee"));

        assertEquals(FlowExecutionStatus.CANCELLED, state.getStatus());
        assertTrue(state.getPendingApprovals().isEmpty());
        ApprovalActionAuditEntry last = state.getApprovalAuditDelta().getLast();
        assertEquals(ApprovalActionType.TERMINATE, last.getAction());
        assertEquals(ADMIN, last.getActorId());
        assertEquals("Filed for the wrong employee", last.getComment());
    }

    @Test
    void theApproverATerminationClearedStillTakesPartByTheirTaskAndTheAdministratorWhoEndedItDoesNot() {
        FlowExecutionState started = start(List.of(ALICE), "AnyOne");
        engine.urgeAsAdministrator(urge(started.getInstanceId()));

        FlowExecutionState state = engine.terminate(terminate(started.getInstanceId(), "Filed for the wrong employee"));

        // Alice never acted: the termination cleared her step, and the history names only the administrator...
        assertFalse(FlowParticipants.isParticipant(state, state.getApprovalAuditDelta(), ALICE));
        assertFalse(FlowParticipants.isParticipant(state, state.getApprovalAuditDelta(), ADMIN));
        // ...but the task the approval gave her stays, and she still sees it; the administrator was given none.
        assertEquals(Set.of(ALICE), taskHolders.get(started.getInstanceId()));
        FlowInstanceAccessGuard guard = new FlowInstanceAccessGuard(mock(FlowInstanceService.class),
                new ApprovalAuditReader(new NoopApprovalActionLedger()), TestFlowRuntimeFactory.taskRows(taskHolders));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, ALICE));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer(state, ADMIN));
    }

    @Test
    void anAdministratorWhoUrgedAnApprovalDoesNotCommentOnIt() {
        FlowExecutionState started = start(List.of(ALICE), "AnyOne");
        engine.urgeAsAdministrator(urge(started.getInstanceId()));

        assertThrows(FlowAuthorizationException.class, () -> engine.addComment(comment(started.getInstanceId(), ADMIN)));
        // Alice, whom it waits on, does.
        assertDoesNotThrow(() -> engine.addComment(comment(started.getInstanceId(), ALICE)));
    }

    @Test
    void onlyARunningWaitingOrFailedInstanceCanBeTerminatedAndAlwaysWithAReason() {
        FlowExecutionState started = start(List.of(ALICE), "AnyOne");
        assertThrows(FlowActionValidationException.class, () -> engine.terminate(terminate(started.getInstanceId(), " ")));

        FlowApproveRequest approve = new FlowApproveRequest();
        approve.setInstanceId(started.getInstanceId());
        approve.setNodeId("approval");
        approve.setActorId(ALICE);
        assertEquals(FlowExecutionStatus.COMPLETED, engine.approve(approve).getStatus());
        assertThrows(FlowActionValidationException.class,
                () -> engine.terminate(terminate(started.getInstanceId(), "Too late")));
    }

    @Test
    void aReassignedTaskIsTheNewApproversAndTheHistoryNamesTheAdministrator() {
        FlowExecutionState started = start(List.of(ALICE), "AnyOne");

        FlowExecutionState state = engine.reassign(reassign(started.getInstanceId(), ALICE, BOB));

        PendingApproval step = state.getPendingApprovals().getFirst();
        assertEquals(List.of(BOB), step.getApprovers());
        ApprovalActionAuditEntry last = state.getApprovalAuditDelta().getLast();
        assertEquals(ApprovalActionType.TRANSFER, last.getAction());
        assertEquals(ALICE, last.getActorId());
        assertEquals(BOB, last.getTargetActorId());
        assertEquals(ADMIN, last.getOperatorId());
        assertEquals("Alice is on leave", last.getComment());
    }

    @Test
    void aNewApproverAlreadyOnTheStepMergesWithTheOldOnesPlace() {
        FlowExecutionState started = start(List.of(ALICE, BOB), "Unanimous");
        assertEquals(2, started.getPendingApprovals().getFirst().getRequiredApprovalCount());

        FlowExecutionState state = engine.reassign(reassign(started.getInstanceId(), ALICE, BOB));

        PendingApproval step = state.getPendingApprovals().getFirst();
        assertEquals(List.of(BOB), step.getApprovers());
        assertEquals(1, step.getTotalApproverCount());
        assertEquals(1, step.getRequiredApprovalCount(), "no more approvals than approvers");
    }

    @Test
    void aMergeThatLeavesTheStepNobodyElseToWaitForPassesIt() {
        // All three must approve; Alice and Bob have. Carol's place, merged into Alice's, needs nobody new.
        FlowExecutionState started = start(List.of(ALICE, BOB, CAROL), "Unanimous");
        engine.approve(approve(started.getInstanceId(), ALICE));
        engine.approve(approve(started.getInstanceId(), BOB));

        FlowExecutionState state = engine.reassign(reassign(started.getInstanceId(), CAROL, ALICE));

        assertEquals(FlowExecutionStatus.COMPLETED, state.getStatus(), "the step has every approval it needs");
        assertTrue(state.getPendingApprovals().isEmpty());
        assertTrue(state.getCompletedNodeIds().contains("approval"));
        ApprovalActionAuditEntry transfer = state.getApprovalAuditDelta().stream()
                .filter(entry -> ApprovalActionType.TRANSFER.equals(entry.getAction()))
                .reduce((first, second) -> second).orElseThrow();
        assertEquals(CAROL, transfer.getActorId());
        assertEquals(ALICE, transfer.getTargetActorId());
        assertEquals(ADMIN, transfer.getOperatorId());
    }

    @Test
    void aMergeThatStillLeavesSomeoneToApproveWaitsForThem() {
        FlowExecutionState started = start(List.of(ALICE, BOB, CAROL), "Unanimous");
        engine.approve(approve(started.getInstanceId(), ALICE));

        FlowExecutionState state = engine.reassign(reassign(started.getInstanceId(), CAROL, ALICE));

        assertEquals(FlowExecutionStatus.WAITING, state.getStatus());
        PendingApproval step = state.getPendingApprovals().getFirst();
        assertEquals(List.of(ALICE, BOB), step.getApprovers());
        assertEquals(2, step.getRequiredApprovalCount());
    }

    private static FlowApproveRequest approve(String instanceId, String actorId) {
        FlowApproveRequest request = new FlowApproveRequest();
        request.setInstanceId(instanceId);
        request.setNodeId("approval");
        request.setActorId(actorId);
        return request;
    }

    private static DesignFlowDefinition flow(List<String> approvers, String approvalMode) {
        return DesignFlowDefinition.builder()
                .name("Administration")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("approval")
                                        .config(Map.of("approverSources", List.of(Map.of("type", "Users", "userIds", approvers)), "approvalMode", approvalMode)).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()))
                        .build())
                .build();
    }
}
