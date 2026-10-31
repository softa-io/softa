package io.softa.starter.flow.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.SubflowTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.api.FlowWithdrawRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.SubflowNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.TimerNodeExecutionHandler;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a flow's scenario decides at run time: only an Approval announces its completion and can be
 * withdrawn, and a Subflow node runs only an Automation flow that declares a Subflow trigger.
 */
class FlowScenarioRuntimeTest {

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService =
            new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final List<FlowNotificationEvent> notices = new ArrayList<>();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.createWithNotifications(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(
                    approvalHandler,
                    new SubflowNodeExecutionHandler(),
                    new TimerNodeExecutionHandler(),
                    new PassThroughNodeExecutionHandler())),
            approvalHandler,
            notices::add);

    @Test
    void anAutomationRunCompletesWithoutAnnouncingIt() {
        publishService.publish("audit", flow(FlowScenario.AUTOMATION, new ApiTrigger(null)));

        assertEquals(FlowExecutionStatus.COMPLETED, engine.startAutomation(start("audit", "user-1")).getStatus());
        assertTrue(notices.stream().noneMatch(FlowNotificationEvent.FlowCompleted.class::isInstance));
    }

    @Test
    void anApprovalAnnouncesItsCompletion() {
        publishService.publish("leave", flowOn("LeaveRequest", FlowScenario.APPROVAL, new SubmitTrigger(),
                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("Manager")
                        .config(Map.of("approvers", List.of("manager"))).build()));
        FlowExecutionState started = engine.start(start("leave", "submitter"));

        FlowApproveRequest approve = new FlowApproveRequest();
        approve.setInstanceId(started.getInstanceId());
        approve.setNodeId("approval");
        approve.setActorId("manager");
        engine.approve(approve);

        assertTrue(notices.stream().anyMatch(n -> n instanceof FlowNotificationEvent.FlowCompleted c && c.approved()));
    }

    @Test
    void onlyAnApprovalCanBeWithdrawn() {
        publishService.publish("delayed", flow(FlowScenario.AUTOMATION, new ApiTrigger(null),
                FlowGraphNode.builder().id("timer").type(FlowNodeType.TIMER).label("Wait")
                        .config(Map.of("durationSeconds", 3600L)).build()));
        FlowExecutionState waiting = engine.startAutomation(start("delayed", "user-1"));
        assertEquals(FlowExecutionStatus.WAITING, waiting.getStatus());

        FlowWithdrawRequest withdraw = new FlowWithdrawRequest();
        withdraw.setInstanceId(waiting.getInstanceId());
        withdraw.setActorId("user-1");

        assertThrows(FlowActionValidationException.class, () -> engine.withdraw(withdraw));
    }

    @Test
    void aSubflowNodeRunsAFlowThatDeclaresASubflowTrigger() {
        publishService.publish("child", flow(FlowScenario.AUTOMATION, new SubflowTrigger()));
        publishService.publish("parent", flow(FlowScenario.AUTOMATION, new ApiTrigger(null), subflowNode("child")));

        assertEquals(FlowExecutionStatus.COMPLETED, engine.startAutomation(start("parent", "user-1")).getStatus());
    }

    @Test
    void aSubflowNodeRefusesAFlowStartedAnotherWay() {
        publishService.publish("child", flow(FlowScenario.AUTOMATION, new ApiTrigger(null)));
        publishService.publish("parent", flow(FlowScenario.AUTOMATION, new ApiTrigger(null), subflowNode("child")));

        FlowExecutionState parent = engine.startAutomation(start("parent", "user-1"));

        assertEquals(FlowExecutionStatus.FAILED, parent.getStatus());
        assertTrue(parent.getErrorMessage().contains("Subflow trigger"), parent.getErrorMessage());
    }

    private static FlowStartRequest start(String flowId, String initiatorId) {
        FlowStartRequest request = new FlowStartRequest();
        request.setFlowId(flowId);
        request.setInitiatorId(initiatorId);
        return request;
    }

    private static FlowGraphNode subflowNode(String childFlowId) {
        return FlowGraphNode.builder().id("subflow").type(FlowNodeType.SUBFLOW).label("Child")
                .config(Map.of("subflowId", childFlowId)).build();
    }

    /** start → {@code middle} (when given) → end, started on no record. */
    private static DesignFlowDefinition flow(FlowScenario scenario, TriggerSource trigger, FlowGraphNode... middle) {
        return flowOn(null, scenario, trigger, middle);
    }

    /** start → {@code middle} (when given) → end, started on records of {@code modelName}. */
    private static DesignFlowDefinition flowOn(String modelName, FlowScenario scenario, TriggerSource trigger,
                                               FlowGraphNode... middle) {
        List<FlowGraphNode> nodes = new ArrayList<>();
        List<FlowGraphEdge> edges = new ArrayList<>();
        nodes.add(FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build());
        String previous = "start";
        for (FlowGraphNode node : middle) {
            nodes.add(node);
            edges.add(FlowGraphEdge.builder().id("to-" + node.getId()).source(previous).target(node.getId()).build());
            previous = node.getId();
        }
        nodes.add(FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build());
        edges.add(FlowGraphEdge.builder().id("to-end").source(previous).target("end").build());
        return DesignFlowDefinition.builder()
                .name(scenario.getType() + " flow")
                .scenario(scenario)
                .modelName(modelName)
                .trigger(trigger)
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(edges).build())
                .build();
    }
}
