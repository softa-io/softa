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
import io.softa.starter.flow.design.trigger.CronTrigger;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.design.trigger.SubflowTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.SubflowNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry;
import io.softa.starter.flow.runtime.trigger.FlowTriggerEvent;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A disabled flow starts nothing — no submission picks it, no change or schedule fires it, no API call or parent
 * flow starts it — while the instances already running carry on: the registry hands out no revision for it, and
 * every way in asks the registry.
 */
class FlowEnablementScenarioTest {

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService =
            new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final DefaultFlowTriggerRegistry triggers = new DefaultFlowTriggerRegistry(bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.create(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(
                    approvalHandler, new SubflowNodeExecutionHandler(), new PassThroughNodeExecutionHandler())),
            approvalHandler);

    @Test
    void aSubmissionDoesNotPickADisabledApprovalFlow() {
        publishService.publish("leave", approvalFlow());

        bundleRegistry.disable("leave");
        assertTrue(triggers.findSubmitFlows("LeaveRequest", Map.of()).isEmpty());

        bundleRegistry.enable("leave");
        assertEquals(1, triggers.findSubmitFlows("LeaveRequest", Map.of()).size());
    }

    @Test
    void aChangeDoesNotFireADisabledFlow() {
        publishService.publish("welcome", flow(FlowScenario.AUTOMATION, new EntityChangeTrigger("Employee", List.of(), null)));
        FlowTriggerEvent created = FlowTriggerEvent.builder().type("EntityChange").sourceModel("Employee")
                .parameters(Map.of()).build();
        assertEquals(1, triggers.findMatchingFlows(created).size());

        bundleRegistry.disable("welcome");

        assertTrue(triggers.findMatchingFlows(created).isEmpty());
    }

    @Test
    void theScheduleOfADisabledFlowStartsNothing() {
        CompiledFlowDefinition nightly = publishService.publish("nightly",
                flow(FlowScenario.AUTOMATION, new CronTrigger("0 0 2 * * ?")));
        assertTrue(triggers.findCronFlow(nightly.getBundleId()).isPresent());

        bundleRegistry.disable("nightly");

        assertTrue(triggers.findCronFlow(nightly.getBundleId()).isEmpty());
    }

    @Test
    void anApiStartOfADisabledFlowIsRefused() {
        publishService.publish("audit", flow(FlowScenario.AUTOMATION, new ApiTrigger(null)));
        bundleRegistry.disable("audit");

        FlowRuntimeException refused = assertThrows(FlowRuntimeException.class,
                () -> engine.startAutomation(start("audit")));

        assertTrue(refused.getMessage().contains("disabled"), refused.getMessage());
    }

    @Test
    void anApiStartPinnedToARevisionOfADisabledFlowIsRefusedToo() {
        CompiledFlowDefinition audit = publishService.publish("audit", flow(FlowScenario.AUTOMATION, new ApiTrigger(null)));
        bundleRegistry.disable("audit");
        FlowStartRequest pinned = start("audit");
        pinned.setFlowId(null);
        pinned.setBundleId(audit.getBundleId());

        FlowRuntimeException refused = assertThrows(FlowRuntimeException.class, () -> engine.startAutomation(pinned));

        assertTrue(refused.getMessage().contains("disabled"), refused.getMessage());
    }

    @Test
    void aParentCannotRunADisabledSubflow() {
        publishService.publish("child", flow(FlowScenario.AUTOMATION, new SubflowTrigger()));
        publishService.publish("parent", flow(FlowScenario.AUTOMATION, new ApiTrigger(null),
                FlowGraphNode.builder().id("subflow").type(FlowNodeType.SUBFLOW).label("Child")
                        .config(Map.of("subflowId", "child")).build()));
        bundleRegistry.disable("child");

        FlowRuntimeException refused = assertThrows(FlowRuntimeException.class,
                () -> engine.startAutomation(start("parent")));

        assertTrue(refused.getMessage().contains("disabled"), refused.getMessage());
    }

    @Test
    void anApprovalAlreadyRunningCarriesOnWhileItsFlowIsDisabled() {
        publishService.publish("leave", approvalFlow());
        FlowExecutionState started = engine.start(start("leave"));
        bundleRegistry.disable("leave");

        FlowApproveRequest approve = new FlowApproveRequest();
        approve.setInstanceId(started.getInstanceId());
        approve.setNodeId("approval");
        approve.setActorId("manager");

        assertEquals(FlowExecutionStatus.COMPLETED, engine.approve(approve).getStatus());
    }

    private static FlowStartRequest start(String flowId) {
        FlowStartRequest request = new FlowStartRequest();
        request.setFlowId(flowId);
        request.setInitiatorId("user-1");
        return request;
    }

    private static DesignFlowDefinition approvalFlow() {
        return flow(FlowScenario.APPROVAL, new SubmitTrigger("LeaveRequest"),
                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("Manager")
                        .config(Map.of("approvers", List.of("manager"))).build());
    }

    /** start → {@code middle} (when given) → end. */
    private static DesignFlowDefinition flow(FlowScenario scenario, TriggerSource trigger, FlowGraphNode... middle) {
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
                .trigger(trigger)
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(edges).build())
                .build();
    }
}
