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
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.WithdrawPolicy;
import io.softa.starter.flow.runtime.api.FlowAddSignAfterRequest;
import io.softa.starter.flow.runtime.api.FlowAddSignBeforeRequest;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle scenarios that need a withdraw-enabled, return-enabled approval flow:
 * withdraw, add-sign (before/after), and return-to-initiator, which ends the instance. Together with
 * {@code FlowActionScenarioTest} these cover the full approval action-handler set.
 */
class FlowApprovalLifecycleScenarioTest {

    private static final String FLOW_ID = "9200";
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
        publishService.publish(FLOW_ID, flowDefinition());
    }

    private FlowExecutionState start() {
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
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
    void beforeTheFirstActionPolicyClosesOnceAnApproverActs() {
        publishService.publish(FLOW_ID, flowDefinition(WithdrawPolicy.BEFORE_FIRST_ACTION, twoStepGraph()));
        FlowExecutionState untouched = start();
        FlowWithdrawRequest early = new FlowWithdrawRequest();
        early.setInstanceId(untouched.getInstanceId());
        early.setActorId(INITIATOR);
        assertEquals(FlowExecutionStatus.WITHDRAWN, engine.withdraw(early).getStatus(),
                "nobody has acted yet, so it can still be taken back");

        FlowExecutionState acted = start();
        FlowApproveRequest approve = new FlowApproveRequest();
        approve.setInstanceId(acted.getInstanceId());
        approve.setNodeId("approval");
        approve.setActorId(APPROVER);
        assertEquals(FlowExecutionStatus.WAITING, engine.approve(approve).getStatus(), "the second step waits");
        FlowWithdrawRequest late = new FlowWithdrawRequest();
        late.setInstanceId(acted.getInstanceId());
        late.setActorId(INITIATOR);
        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> engine.withdraw(late));
        assertTrue(refused.getMessage().contains("already acted"));
    }

    @Test
    void neverPolicyRefusesTheInitiator() {
        publishService.publish(FLOW_ID, flowDefinition(null, twoStepGraph()));
        FlowExecutionState started = start();
        FlowWithdrawRequest req = new FlowWithdrawRequest();
        req.setInstanceId(started.getInstanceId());
        req.setActorId(INITIATOR);
        assertThrows(FlowActionValidationException.class, () -> engine.withdraw(req));
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
    void returnToInitiatorEndsTheInstance() {
        FlowExecutionState started = start();

        FlowReturnRequest ret = new FlowReturnRequest();
        ret.setInstanceId(started.getInstanceId());
        ret.setNodeId("approval");
        ret.setActorId(APPROVER);
        ret.setComment("please add detail");
        FlowExecutionState returned = engine.returnApproval(ret);

        assertEquals(FlowExecutionStatus.RETURNED, returned.getStatus());
        assertTrue(returned.getStatus().isTerminal(), "the initiator submits again as a new instance");
        assertTrue(returned.getPendingApprovals().isEmpty());

        // Nothing more happens on a returned instance.
        FlowWithdrawRequest withdraw = new FlowWithdrawRequest();
        withdraw.setInstanceId(started.getInstanceId());
        withdraw.setActorId(INITIATOR);
        assertThrows(FlowActionValidationException.class, () -> engine.withdraw(withdraw));
    }

    @Test
    void aReturnSaysWhatToChangeAndComesFromTheStepsApprover() {
        FlowExecutionState started = start();

        FlowReturnRequest silent = new FlowReturnRequest();
        silent.setInstanceId(started.getInstanceId());
        silent.setNodeId("approval");
        silent.setActorId(APPROVER);
        silent.setComment(" ");
        assertThrows(FlowActionValidationException.class, () -> engine.returnApproval(silent));

        FlowReturnRequest notTheirs = new FlowReturnRequest();
        notTheirs.setInstanceId(started.getInstanceId());
        notTheirs.setNodeId("approval");
        notTheirs.setActorId(OTHER);
        notTheirs.setComment("please add detail");
        assertThrows(FlowActionValidationException.class, () -> engine.returnApproval(notTheirs));

        assertEquals(FlowExecutionStatus.WAITING, engine.getInstance(started.getInstanceId()).orElseThrow().getStatus(),
                "a refused return changes nothing");
    }

    /** The approval step, then a second one: an approval of the first leaves the instance waiting. */
    private static FlowGraphDocument twoStepGraph() {
        return FlowGraphDocument.builder()
                .nodes(List.of(
                        node("start", FlowNodeType.START),
                        FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("approval")
                                .config(Map.of("approvers", List.of(APPROVER))).build(),
                        FlowGraphNode.builder().id("second").type(FlowNodeType.APPROVAL).label("second")
                                .config(Map.of("approvers", List.of(OTHER))).build(),
                        node("end", FlowNodeType.END)))
                .edges(List.of(
                        edge("e1", "start", "approval"),
                        edge("e2", "approval", "second"),
                        edge("e3", "second", "end")))
                .build();
    }

    private static DesignFlowDefinition flowDefinition() {
        return flowDefinition(WithdrawPolicy.UNTIL_COMPLETED, FlowGraphDocument.builder()
                .nodes(List.of(
                        node("start", FlowNodeType.START),
                        FlowGraphNode.builder()
                                .id("approval")
                                .type(FlowNodeType.APPROVAL)
                                .label("approval")
                                .config(Map.of(
                                        "approvers", List.of(APPROVER),
                                        "returnEnabled", true))
                                .build(),
                        node("end", FlowNodeType.END)))
                .edges(List.of(
                        edge("e1", "start", "approval"),
                        edge("e2", "approval", "end")))
                .build());
    }

    private static DesignFlowDefinition flowDefinition(WithdrawPolicy withdrawPolicy, FlowGraphDocument graph) {
        return DesignFlowDefinition.builder()
                .name("Approval Lifecycle")
                .scenario(FlowScenario.APPROVAL)
                .withdrawPolicy(withdrawPolicy)
                .modelName("LeaveRequest").trigger(new SubmitTrigger())
                .graph(graph)
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }
}
