package io.softa.starter.flow.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowNoApproverException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A step that resolves to nobody under its {@code Error} empty-approver strategy — one message whatever the step's
 * source. A record's submission that reaches it is refused ({@code 400}) and changes nothing: no failed instance, nobody
 * told. Reached after an approval, the approval stands and the instance fails with the message, its initiator and the
 * administrators told: the approver cannot set up an approver. Starts that are not a record's
 * approval keep recording the failed instance.
 */
class NoApproverScenarioTest {

    private static final String FLOW_ID = "9500";
    private static final String INITIATOR = "1001";
    private static final String MANAGER = "2001";
    private static final String MESSAGE = "No approver was found for step 'HR approval'. "
            + "Ask your HR administrator to set one up, then try again.";

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService =
            new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final List<FlowNotificationEvent> notices = new ArrayList<>();
    private final AtomicInteger saves = new AtomicInteger();
    private final StubFlowInstanceStore instanceStore = new StubFlowInstanceStore() {
        @Override
        public FlowExecutionState save(FlowExecutionState state) {
            saves.incrementAndGet();
            return super.save(state);
        }
    };

    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.createWithNotifications(
            bundleRegistry,
            instanceStore,
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler,
            notices::add);

    @Test
    void aRecordsSubmissionIsRefusedAndLeavesNothingBehind() {
        publish(false);

        FlowNoApproverException refused = assertThrows(FlowNoApproverException.class,
                () -> engine.startRecordApproval(startRequest()));

        assertInstanceOf(FlowActionValidationException.class, refused, "answered 400");
        assertEquals(MESSAGE, refused.getMessage());
        assertEquals(0, saves.get(), "no failed instance is kept");
        assertTrue(notices.isEmpty(), "nobody is told: " + notices);
    }

    @Test
    void anotherStartStillRecordsTheFailedInstance() {
        publish(false);

        assertThrows(FlowNoApproverException.class, () -> engine.start(startRequest()));

        List<FlowExecutionState> failed = instanceStore.listByStatus(FlowExecutionStatus.FAILED);
        assertEquals(1, failed.size());
        assertEquals(MESSAGE, failed.getFirst().getErrorMessage());
        assertTrue(notices.stream().anyMatch(FlowNotificationEvent.FlowFailed.class::isInstance));
    }

    @Test
    void anApproveIntoAStepWithNobodyKeepsTheApprovalAndFailsTheInstance() {
        publish(true);
        FlowExecutionState started = engine.startRecordApproval(startRequest());
        assertEquals(FlowExecutionStatus.WAITING, started.getStatus());
        FlowApproveRequest approve = new FlowApproveRequest();
        approve.setInstanceId(started.getInstanceId());
        approve.setNodeId("manager");
        approve.setActorId(MANAGER);

        FlowExecutionState failed = engine.approve(approve);

        assertEquals(FlowExecutionStatus.FAILED, failed.getStatus());
        assertEquals("hr", failed.getFailedNodeId(), "fails at the step nobody approves");
        assertEquals(MESSAGE, failed.getErrorMessage());
        assertEquals("manager", failed.getRetryAfterNodeId(), "run again after the manager's approval");
        assertTrue(failed.getCompletedNodeIds().contains("manager"), "the manager's approval stands");
        assertTrue(failed.getPendingApprovals().isEmpty());
        assertEquals(FlowExecutionStatus.FAILED,
                instanceStore.get(started.getInstanceId()).orElseThrow().getStatus());
        assertTrue(notices.stream().anyMatch(notice -> notice instanceof FlowNotificationEvent.FlowFailed flowFailed
                && "hr".equals(flowFailed.failedNodeId())), "its initiator and the administrators are told");
    }

    /** START → (Manager approval →) HR approval, whose supervisor source finds nobody here, under Error → END. */
    private void publish(boolean managerFirst) {
        List<FlowGraphNode> nodes = new ArrayList<>(List.of(
                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("Start").build(),
                FlowGraphNode.builder().id("hr").type(FlowNodeType.APPROVAL).label("HR approval")
                        .config(Map.of("approverSources", List.of(Map.of("type", "Supervisor")), "emptyApproverStrategy", "Error"))
                        .build(),
                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("End").build()));
        List<FlowGraphEdge> edges = new ArrayList<>(List.of(
                FlowGraphEdge.builder().id("e2").source("hr").target("end").build()));
        if (managerFirst) {
            nodes.add(FlowGraphNode.builder().id("manager").type(FlowNodeType.APPROVAL).label("Manager approval")
                    .config(Map.of("approverSources", List.of(Map.of("type", "Users", "userIds", List.of(MANAGER))))).build());
            edges.add(FlowGraphEdge.builder().id("e0").source("start").target("manager").build());
            edges.add(FlowGraphEdge.builder().id("e1").source("manager").target("hr").build());
        } else {
            edges.add(FlowGraphEdge.builder().id("e1").source("start").target("hr").build());
        }
        publishService.publish(FLOW_ID, DesignFlowDefinition.builder()
                .name("Overtime")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("OvertimeRequest"))
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(edges).build())
                .build());
    }

    private static FlowStartRequest startRequest() {
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
        start.setInitiatorId(INITIATOR);
        start.setModelName("OvertimeRequest");
        start.setRowId("42");
        return start;
    }
}
