package io.softa.starter.flow.runtime;

import java.util.List;
import java.util.Map;
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
import io.softa.starter.flow.runtime.api.FlowReassignRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.api.FlowTerminateRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an administrator does to an approval from the monitoring console: terminate it — the instance cancelled,
 * its history saying who and why — and move an approver's pending task to someone else.
 */
class AdministrationScenarioTest {

    private static final String FLOW_ID = "9300";
    private static final String INITIATOR = "initiator-1";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String ADMIN = "hr-admin";

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.create(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler);

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

    private static DesignFlowDefinition flow(List<String> approvers, String approvalMode) {
        return DesignFlowDefinition.builder()
                .name("Administration")
                .scenario(FlowScenario.APPROVAL)
                .modelName("LeaveRequest").trigger(new SubmitTrigger())
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("approval")
                                        .config(Map.of("approvers", approvers, "approvalMode", approvalMode)).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()))
                        .build())
                .build();
    }
}
