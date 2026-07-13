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
import io.softa.starter.flow.runtime.api.FlowAddSignAfterRequest;
import io.softa.starter.flow.runtime.api.FlowAddSignBeforeRequest;
import io.softa.starter.flow.runtime.api.FlowResubmitRequest;
import io.softa.starter.flow.runtime.api.FlowReturnRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.api.FlowWithdrawRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
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
 * Lifecycle scenarios that need a withdraw-enabled, return-enabled approval flow:
 * withdraw, add-sign (before/after), and return-to-initiator → resubmit. Together with
 * {@code FlowActionScenarioTest} these cover the full approval action-handler set.
 */
class FlowApprovalLifecycleScenarioTest {

    private static final Long DESIGN_ID = 9200L;
    private static final String APPROVER = "manager-1";
    private static final String OTHER = "manager-2";
    private static final String INITIATOR = "initiator-1";

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
        publishService.publish(DESIGN_ID, flowDefinition());
    }

    private FlowExecutionState start() {
        FlowStartRequest start = new FlowStartRequest();
        start.setDesignId(DESIGN_ID);
        start.setInitiatorId(INITIATOR);
        FlowExecutionState state = engine.start(start);
        assertEquals(FlowExecutionStatus.WAITING, state.getStatus());
        return state;
    }

    @Test
    void initiatorCanWithdraw() {
        FlowExecutionState started = start();
        FlowWithdrawRequest req = new FlowWithdrawRequest();
        req.setInstanceId(started.getInstanceId());
        req.setActorId(INITIATOR);
        req.setComment("changed my mind");
        assertEquals(FlowExecutionStatus.WITHDRAWN, engine.withdraw(req).getStatus());
    }

    @Test
    void nonInitiatorCannotWithdraw() {
        FlowExecutionState started = start();
        FlowWithdrawRequest req = new FlowWithdrawRequest();
        req.setInstanceId(started.getInstanceId());
        req.setActorId(OTHER);
        assertThrows(FlowActionValidationException.class, () -> engine.withdraw(req));
    }

    @Test
    void addSignBeforeKeepsFlowWaiting() {
        FlowExecutionState started = start();
        FlowAddSignBeforeRequest req = new FlowAddSignBeforeRequest();
        req.setInstanceId(started.getInstanceId());
        req.setNodeId("approval");
        req.setActorId(APPROVER);
        req.setTargetActorId(OTHER);
        assertEquals(FlowExecutionStatus.WAITING, engine.addSignBefore(req).getStatus());
    }

    @Test
    void addSignAfterKeepsFlowWaiting() {
        FlowExecutionState started = start();
        FlowAddSignAfterRequest req = new FlowAddSignAfterRequest();
        req.setInstanceId(started.getInstanceId());
        req.setNodeId("approval");
        req.setActorId(APPROVER);
        req.setTargetActorId(OTHER);
        assertEquals(FlowExecutionStatus.WAITING, engine.addSignAfter(req).getStatus());
    }

    @Test
    void addSignToSelfIsRejected() {
        FlowExecutionState started = start();
        FlowAddSignBeforeRequest req = new FlowAddSignBeforeRequest();
        req.setInstanceId(started.getInstanceId());
        req.setNodeId("approval");
        req.setActorId(APPROVER);
        req.setTargetActorId(APPROVER);
        assertThrows(FlowActionValidationException.class, () -> engine.addSignBefore(req));
    }

    @Test
    void returnToInitiatorThenResubmitReopensApproval() {
        FlowExecutionState started = start();

        FlowReturnRequest ret = new FlowReturnRequest();
        ret.setInstanceId(started.getInstanceId());
        ret.setNodeId("approval");
        ret.setActorId(APPROVER);
        ret.setComment("please add detail");
        assertEquals(FlowExecutionStatus.RETURNED, engine.returnApproval(ret).getStatus());

        FlowResubmitRequest resubmit = new FlowResubmitRequest();
        resubmit.setInstanceId(started.getInstanceId());
        resubmit.setActorId(INITIATOR);
        FlowExecutionState afterResubmit = engine.resubmit(resubmit);
        assertEquals(FlowExecutionStatus.WAITING, afterResubmit.getStatus());
        assertEquals(1, afterResubmit.getResubmissionCount());
    }

    @Test
    void nonInitiatorCannotResubmit() {
        FlowExecutionState started = start();
        FlowReturnRequest ret = new FlowReturnRequest();
        ret.setInstanceId(started.getInstanceId());
        ret.setNodeId("approval");
        ret.setActorId(APPROVER);
        engine.returnApproval(ret);

        FlowResubmitRequest resubmit = new FlowResubmitRequest();
        resubmit.setInstanceId(started.getInstanceId());
        resubmit.setActorId(OTHER);
        assertThrows(FlowActionValidationException.class, () -> engine.resubmit(resubmit));
    }

    private static DesignFlowDefinition flowDefinition() {
        return DesignFlowDefinition.builder()
                .code("approval-lifecycle")
                .name("Approval Lifecycle")
                .scenario(FlowScenario.PROCESS)
                .allowInitiatorWithdraw(true)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of(
                                                "approvers", List.of(APPROVER),
                                                "returnEnabled", true,
                                                "returnTarget", "Initiator"))
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
