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
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.StubFlowBundleRegistry;
import io.softa.starter.flow.runtime.StubFlowInstanceStore;
import io.softa.starter.flow.runtime.TestFlowRuntimeFactory;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * In-process {@code FlowClient} contract: the port delegates to the engine AND resolves the caller from
 * {@link ContextHolder}. {@code start} records the context's user as initiator; {@code approve} carries no
 * {@code actorId}, so it can only succeed because {@code FlowActors.currentUserId()} ("100") matches the
 * node's approver. Only the {@code engine} delegate is exercised here, so the other bean dependencies are
 * left null.
 */
class InProcessFlowClientContractTest {

    private static final String AUTOMATION_FLOW_ID = "9301";
    private static final String APPROVAL_FLOW_ID = "9300";
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
    void publishFlows() {
        publishService.publish(AUTOMATION_FLOW_ID, automationFlow());
        publishService.publish(APPROVAL_FLOW_ID, approvalFlow());
    }

    @Test
    void startResolvesTheInitiatorFromContext() {
        ContextHolder.runWith(userContext(), () -> {
            FlowStartRequest start = new FlowStartRequest();
            start.setFlowId(AUTOMATION_FLOW_ID);
            // initiatorId intentionally not set — resolved from context
            FlowExecutionState started = client.start(start);

            assertEquals(FlowExecutionStatus.COMPLETED, started.getStatus());
            assertEquals(ACTOR, started.getInitiatorId());
        });
    }

    @Test
    void startRefusesAnApprovalFlow() {
        ContextHolder.runWith(userContext(), () -> {
            FlowStartRequest start = new FlowStartRequest();
            start.setFlowId(APPROVAL_FLOW_ID);

            assertThrows(FlowActionValidationException.class, () -> client.start(start));
        });
    }

    @Test
    void approveResolvesTheActorFromContext() {
        // An approval starts by record submission, which needs a host model; the engine starts it directly here.
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(APPROVAL_FLOW_ID);
        start.setInitiatorId("initiator-1");
        FlowExecutionState started = engine.start(start);
        assertEquals(FlowExecutionStatus.WAITING, started.getStatus());

        ContextHolder.runWith(userContext(), () -> {
            FlowApproveRequest approve = new FlowApproveRequest();
            approve.setInstanceId(started.getInstanceId());
            approve.setNodeId("approval");
            // actorId intentionally not set — resolved from context as "100", the node's approver
            FlowExecutionState done = client.approve(approve);
            assertEquals(FlowExecutionStatus.COMPLETED, done.getStatus());
        });
    }

    private static Context userContext() {
        Context ctx = new Context();
        ctx.setUserId(USER_ID);
        return ctx;
    }

    private static DesignFlowDefinition automationFlow() {
        return DesignFlowDefinition.builder()
                .name("In-Process Contract Automation")
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(node("start", FlowNodeType.START), node("end", FlowNodeType.END)))
                        .edges(List.of(edge("e1", "start", "end")))
                        .build())
                .build();
    }

    private static DesignFlowDefinition approvalFlow() {
        return DesignFlowDefinition.builder()
                .name("In-Process Contract Approval")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
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
