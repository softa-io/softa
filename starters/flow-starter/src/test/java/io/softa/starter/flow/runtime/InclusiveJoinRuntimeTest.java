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
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.InclusiveGatewayNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The join an inclusive gateway is paired with waits for the branches the gateway took, and no others: the ones it
 * did not take count as arrived. It goes on once, after the last of them.
 *
 * <p>The flow: a claim over 1 000 goes to finance, one abroad to travel — both when both hold — and HR approves after
 * them; with neither, straight to HR.
 */
class InclusiveJoinRuntimeTest {

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final StubFlowInstanceStore instanceStore = new StubFlowInstanceStore();
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.create(bundleRegistry, instanceStore,
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new InclusiveGatewayNodeExecutionHandler(),
                    new PassThroughNodeExecutionHandler())),
            approvalHandler);

    {
        new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry).publish("claims", claimFlow());
    }

    @Test
    void oneBranchTakenGoesOnToHrOnceItIsApproved() {
        FlowExecutionState state = start(5000, false);
        assertEquals(List.of("finance"), waitingOn(state));

        approve(state, "finance");

        assertEquals(List.of("hr"), waitingOn(state));
    }

    @Test
    void bothBranchesTakenGoOnToHrOnlyOnceBothAreApproved() {
        FlowExecutionState state = start(5000, true);
        assertEquals(List.of("finance", "travel"), waitingOn(state));

        approve(state, "finance");
        assertEquals(List.of("travel"), waitingOn(state), "the join still waits for travel");

        approve(state, "travel");
        assertEquals(List.of("hr"), waitingOn(state), "HR is asked once");

        approve(state, "hr");
        assertEquals(FlowExecutionStatus.COMPLETED, state.getStatus());
    }

    @Test
    void neitherBranchTakenGoesStraightToHr() {
        FlowExecutionState state = start(10, false);

        assertEquals(List.of("hr"), waitingOn(state));
    }

    private FlowExecutionState start(int amount, boolean abroad) {
        FlowStartRequest request = new FlowStartRequest();
        request.setFlowId("claims");
        request.setInitiatorId("initiator-1");
        request.setVariables(Map.of("amount", amount, "abroad", abroad));
        return engine.start(request);
    }

    private void approve(FlowExecutionState state, String nodeId) {
        FlowApproveRequest request = new FlowApproveRequest();
        request.setInstanceId(state.getInstanceId());
        request.setNodeId(nodeId);
        request.setActorId(nodeId + "-approver");
        request.setComment("ok");
        engine.approve(request);
    }

    private List<String> waitingOn(FlowExecutionState state) {
        return instanceStore.get(state.getInstanceId()).orElseThrow().getPendingApprovals().stream()
                .map(PendingApproval::getNodeId)
                .toList();
    }

    private static DesignFlowDefinition claimFlow() {
        List<FlowGraphNode> nodes = new ArrayList<>(List.of(
                node("start", FlowNodeType.START), node("gateway", FlowNodeType.INCLUSIVE_GATEWAY),
                approval("finance"), approval("travel"), node("join", FlowNodeType.PARALLEL_JOIN), approval("hr"),
                node("end", FlowNodeType.END)));
        List<FlowGraphEdge> edges = List.of(
                edge("start-gateway", "start", "gateway", null),
                edge("g-finance", "gateway", "finance", "amount > 1000"),
                edge("g-travel", "gateway", "travel", "abroad == true"),
                edge("g-join", "gateway", "join", null),
                edge("finance-join", "finance", "join", null),
                edge("travel-join", "travel", "join", null),
                edge("join-hr", "join", "hr", null),
                edge("hr-end", "hr", "end", null));
        return DesignFlowDefinition.builder()
                .name("Expense claim")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(edges).build())
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    /** An approval step whose one approver is {@code <id>-approver}. */
    private static FlowGraphNode approval(String id) {
        return FlowGraphNode.builder().id(id).type(FlowNodeType.APPROVAL).label(id)
                .config(Map.of("approverSource", Map.of("type", "Users", "userIds", List.of(id + "-approver")))).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target, String condition) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).conditionExpression(condition).build();
    }
}
