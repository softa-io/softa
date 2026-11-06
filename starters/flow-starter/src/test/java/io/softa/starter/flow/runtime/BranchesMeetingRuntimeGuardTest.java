package io.softa.starter.flow.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowTransition;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime safety net under the publish checks, for revisions published before them: a step two branches reach
 * without a join is not run a second time, and a join left short does not let the approval complete. Either way the
 * instance fails, naming the node; the approval that reached it stands, as any after which the approval cannot go on
 * (zingkey-hcm#2385).
 */
class BranchesMeetingRuntimeGuardTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final StubFlowInstanceStore instanceStore = new StubFlowInstanceStore();
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.create(bundleRegistry, instanceStore,
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler);

    @Test
    void aStepTwoBranchesReachIsNotAskedForASecondTime() {
        // fork → finance → hr, fork → travel → hr: published before CONCURRENT_BRANCHES_MEET refused it.
        publishOld("8101", flow(
                List.of(node("fork", FlowNodeType.PARALLEL_FORK), approval("finance"), approval("travel"),
                        approval("hr")),
                List.of(edge("start-fork", "start", "fork"),
                        edge("fork-finance", "fork", "finance"),
                        edge("fork-travel", "fork", "travel"),
                        edge("finance-end", "finance", "end"),
                        edge("travel-hr", "travel", "hr"),
                        edge("hr-end", "hr", "end"))),
                compiled -> retarget(compiled, "finance-end", "hr"));
        FlowExecutionState state = start("8101", Map.of());

        approve(state, "travel");
        assertEquals(1, pendingAt(state, "hr"));

        approve(state, "finance");

        FlowExecutionState failed = instanceStore.get(state.getInstanceId()).orElseThrow();
        assertEquals(FlowExecutionStatus.FAILED, failed.getStatus());
        assertTrue(failed.getErrorMessage().contains("node 'hr' a second time"), failed.getErrorMessage());
        assertEquals(1, pendingAt(failed, "hr"), "HR is not asked a second time");
    }

    @Test
    void aJoinLeftShortFailsTheInstanceInsteadOfCompletingIt() {
        // Finance may end instead of joining: published before BRANCH_SKIPS_JOIN refused it.
        publishOld("8102", flow(
                List.of(node("fork", FlowNodeType.PARALLEL_FORK), approval("finance"), approval("travel"),
                        node("join", FlowNodeType.PARALLEL_JOIN), approval("hr")),
                List.of(edge("start-fork", "start", "fork"),
                        edge("fork-finance", "fork", "finance"),
                        edge("fork-travel", "fork", "travel"),
                        edge("finance-join", "finance", "join"),
                        edge("travel-join", "travel", "join"),
                        edge("join-hr", "join", "hr"),
                        edge("hr-end", "hr", "end"))),
                compiled -> {
                    compiled.getTransitionIndex().get("finance-join").setConditionExpression("amount > 1000");
                    connect(compiled, "finance-end", "finance", "end");
                });
        FlowExecutionState state = start("8102", Map.of("amount", 500L));

        approve(state, "finance");
        assertEquals(FlowExecutionStatus.WAITING, state.getStatus(), "travel still to approve");

        approve(state, "travel");

        FlowExecutionState failed = instanceStore.get(state.getInstanceId()).orElseThrow();
        assertEquals(FlowExecutionStatus.FAILED, failed.getStatus());
        assertTrue(failed.getErrorMessage().contains("parallel join 'join' has had 1 of its 2 branches"),
                failed.getErrorMessage());
        assertTrue(instanceStore.listByStatus(FlowExecutionStatus.COMPLETED).isEmpty(),
                "the approval is not completed past the join");
    }

    private FlowExecutionState start(String flowId, Map<String, Object> variables) {
        FlowStartRequest request = new FlowStartRequest();
        request.setFlowId(flowId);
        request.setInitiatorId("initiator-1");
        request.setVariables(variables);
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

    private static long pendingAt(FlowExecutionState state, String nodeId) {
        return state.getPendingApprovals().stream().filter(pending -> nodeId.equals(pending.getNodeId())).count();
    }

    /** Registers {@code design} as a revision published before the checks: compiled, then changed by {@code change}. */
    private void publishOld(String flowId, DesignFlowDefinition design, Consumer<CompiledFlowDefinition> change) {
        CompiledFlowDefinition compiled = compiler.compile(design);
        change.accept(compiled);
        bundleRegistry.register(compiled, design, flowId);
    }

    private static void retarget(CompiledFlowDefinition compiled, String edgeId, String target) {
        CompiledFlowTransition transition = compiled.getTransitionIndex().get(edgeId);
        CompiledFlowNode from = compiled.getNodeIndex().get(transition.getTarget());
        List<String> incoming = new ArrayList<>(from.getIncomingEdgeIds());
        incoming.remove(edgeId);
        from.setIncomingEdgeIds(incoming);
        transition.setTarget(target);
        CompiledFlowNode to = compiled.getNodeIndex().get(target);
        to.setIncomingEdgeIds(append(to.getIncomingEdgeIds(), edgeId));
    }

    private static void connect(CompiledFlowDefinition compiled, String edgeId, String source, String target) {
        compiled.getTransitionIndex().put(edgeId,
                CompiledFlowTransition.builder().edgeId(edgeId).source(source).target(target).build());
        CompiledFlowNode from = compiled.getNodeIndex().get(source);
        from.setOutgoingEdgeIds(append(from.getOutgoingEdgeIds(), edgeId));
        CompiledFlowNode to = compiled.getNodeIndex().get(target);
        to.setIncomingEdgeIds(append(to.getIncomingEdgeIds(), edgeId));
    }

    private static List<String> append(List<String> ids, String id) {
        List<String> all = new ArrayList<>(ids);
        all.add(id);
        return all;
    }

    /** START and END around {@code nodes}, wired by {@code edges}. */
    private static DesignFlowDefinition flow(List<FlowGraphNode> nodes, List<FlowGraphEdge> edges) {
        List<FlowGraphNode> all = new ArrayList<>(List.of(node("start", FlowNodeType.START),
                node("end", FlowNodeType.END)));
        all.addAll(nodes);
        return DesignFlowDefinition.builder()
                .name("Expense claim")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder().nodes(all).edges(edges).build())
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

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }
}
