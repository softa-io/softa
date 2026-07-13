package io.softa.starter.flow.runtime.engine;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.api.FlowClient;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.StubFlowBundleRegistry;
import io.softa.starter.flow.runtime.StubFlowInstanceStore;
import io.softa.starter.flow.runtime.TestFlowRuntimeFactory;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * In-process {@code FlowClient} contract: start → pending → approve through the
 * {@link InProcessFlowClient} port. Proves the port delegates to the engine AND resolves the
 * actor from {@link ContextHolder} — the approve carries no {@code actorId}, so it can only
 * succeed because {@code me()} ("100") matches the node's approver. Only the {@code engine}
 * delegate is exercised here, so the other bean dependencies are left null.
 */
class InProcessFlowClientContractTest {

    private static final Long DESIGN_ID = 9300L;
    private static final Long USER_ID = 100L;
    private static final String ACTOR = "100"; // String.valueOf(USER_ID)

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(compiler, bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();

    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.create(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler);

    private final FlowClient client =
            new InProcessFlowClient(engine, null, null, null, null, null, null, null, null);

    @BeforeEach
    void publishFlow() {
        publishService.publish(DESIGN_ID, flowDefinition());
    }

    @Test
    void startThenApproveResolvesActorFromContext() {
        Context ctx = new Context();
        ctx.setUserId(USER_ID);
        ContextHolder.runWith(ctx, () -> {
            FlowStartRequest start = new FlowStartRequest();
            start.setDesignId(DESIGN_ID);
            // initiatorId intentionally not set — resolved from context
            FlowExecutionState started = client.start(start);
            assertEquals(FlowExecutionStatus.WAITING, started.getStatus());

            FlowApproveRequest approve = new FlowApproveRequest();
            approve.setInstanceId(started.getInstanceId());
            approve.setNodeId("approval");
            // actorId intentionally not set — resolved from context as "100", the node's approver
            FlowExecutionState done = client.approve(approve);
            assertEquals(FlowExecutionStatus.COMPLETED, done.getStatus());
        });
    }

    private static DesignFlowDefinition flowDefinition() {
        return DesignFlowDefinition.builder()
                .code("inprocess-contract")
                .name("In-Process Contract")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of("approvers", List.of(ACTOR)))
                                        .build(),
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
