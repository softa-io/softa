package io.softa.starter.flow.runtime;

import java.util.LinkedHashMap;
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
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An approval step's groups of approvers make one pool, which its approval mode counts over: with two groups, an
 * any-one step passes on either group's approval and an all-approve step waits for both — "the manager or HR" and "the
 * manager and HR" in one step, with no fork drawn. Someone two groups name is one approver.
 */
class ApproverGroupsScenarioTest {

    private static final String MANAGER = "1001";
    private static final String HR = "1002";

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.create(bundleRegistry, new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler);

    @Test
    void eitherGroupsApprovalPassesAnAnyOneStep() {
        FlowExecutionState state = start("AnyOne", people(MANAGER), people(HR));

        PendingApproval step = state.getPendingApprovals().getFirst();
        assertEquals(List.of(MANAGER, HR), step.getApprovers());
        assertEquals(1, step.getRequiredApprovalCount());
        assertEquals(FlowExecutionStatus.COMPLETED, engine.approve(approve(state, HR)).getStatus());
    }

    @Test
    void anAllApproveStepWaitsForEveryoneItsGroupsFind() {
        // The manager is in both groups: one approver, counted once.
        FlowExecutionState state = start("Unanimous", people(MANAGER), people(HR, MANAGER));

        PendingApproval step = state.getPendingApprovals().getFirst();
        assertEquals(List.of(MANAGER, HR), step.getApprovers());
        assertEquals(2, step.getRequiredApprovalCount());
        assertEquals(FlowExecutionStatus.WAITING, engine.approve(approve(state, MANAGER)).getStatus());
        assertEquals(FlowExecutionStatus.COMPLETED, engine.approve(approve(state, HR)).getStatus());
    }

    private static Map<String, Object> people(String... userIds) {
        return Map.of("type", "Users", "userIds", List.of(userIds));
    }

    private static FlowApproveRequest approve(FlowExecutionState state, String actor) {
        FlowApproveRequest request = new FlowApproveRequest();
        request.setInstanceId(state.getInstanceId());
        request.setNodeId("approval");
        request.setActorId(actor);
        return request;
    }

    @SafeVarargs
    private FlowExecutionState start(String approvalMode, Map<String, Object>... groups) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("approvalMode", approvalMode);
        config.put("approverSources", List.of(groups));
        publishService.publish("groups", DesignFlowDefinition.builder()
                .name("Groups")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("approval")
                                        .config(config).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()))
                        .build())
                .build());
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId("groups");
        start.setInitiatorId("initiator-1");
        FlowExecutionState state = engine.start(start);
        assertEquals(FlowExecutionStatus.WAITING, state.getStatus());
        return state;
    }
}
