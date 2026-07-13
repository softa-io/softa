package io.softa.starter.flow.runtime;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.enums.ApproverDedupStrategy;
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
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Approver dedup (审批人去重) over a 3-node flow whose approvers are X → Y → X (the repeat of X is
 * NON-contiguous, separated by Y). Demonstrates the GLOBAL vs CONTIGUOUS distinction the design
 * hinges on: GLOBAL auto-approves X at the third node (X already endorsed this document), while
 * CONTIGUOUS does not (X did not approve the immediately-preceding node, Y's).
 */
class ApproverDedupScenarioTest {

    private static final String X = "manager-1";
    private static final String Y = "manager-2";

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(compiler, bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();

    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.create(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler);

    /** Start, approve node1 (X), then node2 (Y); return the state after node2's approval. */
    private FlowExecutionState startApproveX1ThenY2(Long designId) {
        FlowStartRequest start = new FlowStartRequest();
        start.setDesignId(designId);
        start.setInitiatorId("initiator-1");
        FlowExecutionState started = engine.start(start);
        assertEquals(FlowExecutionStatus.WAITING, started.getStatus());

        FlowApproveRequest a1 = new FlowApproveRequest();
        a1.setInstanceId(started.getInstanceId());
        a1.setNodeId("ap1");
        a1.setActorId(X);
        FlowExecutionState afterA1 = engine.approve(a1);
        assertEquals(FlowExecutionStatus.WAITING, afterA1.getStatus(), "node2 (Y) must wait");

        FlowApproveRequest a2 = new FlowApproveRequest();
        a2.setInstanceId(started.getInstanceId());
        a2.setNodeId("ap2");
        a2.setActorId(Y);
        return engine.approve(a2);
    }

    @Test
    void globalAutoApprovesNonContiguousSameApprover() {
        publishService.publish(7401L, threeNodeFlow(ApproverDedupStrategy.GLOBAL));
        FlowExecutionState after = startApproveX1ThenY2(7401L);
        assertEquals(FlowExecutionStatus.COMPLETED, after.getStatus(),
                "GLOBAL: X already approved ap1, so ap3 (X) auto-approves and the flow completes");
    }

    @Test
    void contiguousDoesNotAutoApproveNonContiguousSameApprover() {
        publishService.publish(7402L, threeNodeFlow(ApproverDedupStrategy.CONTIGUOUS));
        FlowExecutionState after = startApproveX1ThenY2(7402L);
        assertEquals(FlowExecutionStatus.WAITING, after.getStatus(),
                "CONTIGUOUS: the node before ap3 was Y's, not X's, so ap3 must still wait for X");
    }

    @Test
    void noneRequiresApprovalAtEveryNode() {
        publishService.publish(7403L, threeNodeFlow(ApproverDedupStrategy.NONE));
        FlowExecutionState after = startApproveX1ThenY2(7403L);
        assertEquals(FlowExecutionStatus.WAITING, after.getStatus(),
                "NONE: ap3 must wait for X regardless of prior approvals");
    }

    private static DesignFlowDefinition threeNodeFlow(ApproverDedupStrategy dedup) {
        return DesignFlowDefinition.builder()
                .code("dedup-" + dedup)
                .name("Approver Dedup " + dedup)
                .scenario(FlowScenario.PROCESS)
                .approverDedup(dedup)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                approvalNode("ap1", X),
                                approvalNode("ap2", Y),
                                approvalNode("ap3", X),
                                node("end", FlowNodeType.END)))
                        .edges(List.of(
                                edge("e1", "start", "ap1"),
                                edge("e2", "ap1", "ap2"),
                                edge("e3", "ap2", "ap3"),
                                edge("e4", "ap3", "end")))
                        .build())
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    private static FlowGraphNode approvalNode(String id, String approverId) {
        return FlowGraphNode.builder()
                .id(id)
                .type(FlowNodeType.APPROVAL)
                .label(id)
                .config(Map.of("approvers", List.of(approverId)))
                .build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }
}
