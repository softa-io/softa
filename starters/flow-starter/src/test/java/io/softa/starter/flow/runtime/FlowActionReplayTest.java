package io.softa.starter.flow.runtime;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowRejectRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * At-least-once replay idempotency: replaying a completing approve/reject (whose
 * pending was already removed) returns the current state instead of throwing or double-applying.
 */
class FlowActionReplayTest {

    private static final Long DESIGN_ID = 7600L;
    private static final String X = "manager-1";

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(compiler, bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();

    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.create(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler);

    @BeforeEach
    void publishFlow() {
        publishService.publish(DESIGN_ID, singleApproverFlow());
    }

    private FlowExecutionState start() {
        FlowStartRequest start = new FlowStartRequest();
        start.setDesignId(DESIGN_ID);
        start.setInitiatorId("initiator-1");
        FlowExecutionState state = engine.start(start);
        assertEquals(FlowExecutionStatus.WAITING, state.getStatus());
        return state;
    }

    @Test
    void replayedApproveReturnsCurrentStateInsteadOfThrowing() {
        FlowExecutionState started = start();
        FlowApproveRequest req = new FlowApproveRequest();
        req.setInstanceId(started.getInstanceId());
        req.setNodeId("approval");
        req.setActorId(X);

        assertEquals(FlowExecutionStatus.COMPLETED, engine.approve(req).getStatus());
        // Redelivery / retry of the SAME completing approve — node is already gone.
        FlowExecutionState replay = assertDoesNotThrow(() -> engine.approve(req));
        assertEquals(FlowExecutionStatus.COMPLETED, replay.getStatus(), "replay must be an idempotent no-op");
    }

    @Test
    void replayedRejectReturnsCurrentStateInsteadOfThrowing() {
        FlowExecutionState started = start();
        FlowRejectRequest req = new FlowRejectRequest();
        req.setInstanceId(started.getInstanceId());
        req.setNodeId("approval");
        req.setActorId(X);
        req.setComment("no");

        assertEquals(FlowExecutionStatus.REJECTED, engine.reject(req).getStatus());
        FlowExecutionState replay = assertDoesNotThrow(() -> engine.reject(req));
        assertEquals(FlowExecutionStatus.REJECTED, replay.getStatus(), "replay must be an idempotent no-op");
    }

    private static DesignFlowDefinition singleApproverFlow() {
        return DesignFlowDefinition.builder()
                .code("replay-flow")
                .name("Replay Flow")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("approval")
                                        .config(Map.of("approvers", List.of(X))).build(),
                                node("end", FlowNodeType.END)))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")))
                        .build())
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }
}
