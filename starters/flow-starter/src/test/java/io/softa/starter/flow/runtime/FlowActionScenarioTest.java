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
import io.softa.starter.flow.runtime.api.FlowCommentRequest;
import io.softa.starter.flow.runtime.api.FlowDelegateRequest;
import io.softa.starter.flow.runtime.api.FlowRejectRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.api.FlowTransferRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Action-handler scenarios over a single-approver flow (START → Approval → END):
 * approve / reject / transfer / delegate / comment, plus actor-validation errors.
 * Covers the approval state machine transitions that the single end-to-end test
 * (approve only) left untested.
 */
class FlowActionScenarioTest {

    private static final Long DESIGN_ID = 9100L;
    private static final String APPROVER = "manager-1";
    private static final String OTHER = "manager-2";

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

    private FlowExecutionState startAndExpectPending() {
        FlowStartRequest start = new FlowStartRequest();
        start.setDesignId(DESIGN_ID);
        start.setInitiatorId("initiator-1");
        FlowExecutionState state = engine.start(start);
        assertEquals(FlowExecutionStatus.WAITING, state.getStatus());
        assertFalse(state.getPendingApprovals().isEmpty());
        return state;
    }

    @Test
    void approveCompletesFlow() {
        FlowExecutionState started = startAndExpectPending();
        FlowApproveRequest req = new FlowApproveRequest();
        req.setInstanceId(started.getInstanceId());
        req.setNodeId("approval");
        req.setActorId(APPROVER);
        assertEquals(FlowExecutionStatus.COMPLETED, engine.approve(req).getStatus());
    }

    @Test
    void rejectTerminatesFlow() {
        FlowExecutionState started = startAndExpectPending();
        FlowRejectRequest req = new FlowRejectRequest();
        req.setInstanceId(started.getInstanceId());
        req.setNodeId("approval");
        req.setActorId(APPROVER);
        req.setComment("not acceptable");
        assertEquals(FlowExecutionStatus.REJECTED, engine.reject(req).getStatus());
    }

    @Test
    void transferReassignsApproverThenNewApproverCompletes() {
        FlowExecutionState started = startAndExpectPending();
        FlowTransferRequest transfer = new FlowTransferRequest();
        transfer.setInstanceId(started.getInstanceId());
        transfer.setNodeId("approval");
        transfer.setActorId(APPROVER);
        transfer.setTargetActorId(OTHER);

        FlowExecutionState afterTransfer = engine.transfer(transfer);
        assertEquals(FlowExecutionStatus.WAITING, afterTransfer.getStatus());
        PendingApproval pending = afterTransfer.getPendingApprovals().get(0);
        assertTrue(pending.getApprovers().contains(OTHER), "transfer target must become the pending approver");
        assertFalse(pending.getApprovers().contains(APPROVER), "original approver must be replaced");

        FlowApproveRequest approve = new FlowApproveRequest();
        approve.setInstanceId(started.getInstanceId());
        approve.setNodeId("approval");
        approve.setActorId(OTHER);
        assertEquals(FlowExecutionStatus.COMPLETED, engine.approve(approve).getStatus());
    }

    @Test
    void delegateKeepsFlowWaitingForDelegate() {
        FlowExecutionState started = startAndExpectPending();
        FlowDelegateRequest delegate = new FlowDelegateRequest();
        delegate.setInstanceId(started.getInstanceId());
        delegate.setNodeId("approval");
        delegate.setActorId(APPROVER);
        delegate.setTargetActorId(OTHER);

        FlowExecutionState afterDelegate = engine.delegate(delegate);
        assertEquals(FlowExecutionStatus.WAITING, afterDelegate.getStatus());
        assertTrue(afterDelegate.getPendingApprovals().get(0).getApprovers().contains(OTHER));
    }

    @Test
    void commentLeavesFlowWaitingAndRecordsHistory() {
        FlowExecutionState started = startAndExpectPending();
        int historyBefore = started.getApprovalAuditDelta().size();
        FlowCommentRequest comment = new FlowCommentRequest();
        comment.setInstanceId(started.getInstanceId());
        comment.setNodeId("approval");
        comment.setActorId(APPROVER);
        comment.setComment("please clarify");

        FlowExecutionState afterComment = engine.addComment(comment);
        assertEquals(FlowExecutionStatus.WAITING, afterComment.getStatus());
        assertTrue(afterComment.getApprovalAuditDelta().size() >= historyBefore);
    }

    @Test
    void rejectByNonApproverIsRejectedWithValidationError() {
        FlowExecutionState started = startAndExpectPending();
        FlowRejectRequest req = new FlowRejectRequest();
        req.setInstanceId(started.getInstanceId());
        req.setNodeId("approval");
        req.setActorId("stranger");
        req.setComment("I should not be able to do this");
        assertThrows(FlowActionValidationException.class, () -> engine.reject(req));
    }

    @Test
    void transferToSelfIsRejected() {
        FlowExecutionState started = startAndExpectPending();
        FlowTransferRequest transfer = new FlowTransferRequest();
        transfer.setInstanceId(started.getInstanceId());
        transfer.setNodeId("approval");
        transfer.setActorId(APPROVER);
        transfer.setTargetActorId(APPROVER);
        assertThrows(FlowActionValidationException.class, () -> engine.transfer(transfer));
    }

    private static DesignFlowDefinition flowDefinition() {
        return DesignFlowDefinition.builder()
                .code("action-scenarios")
                .name("Action Scenarios")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                approvalNode("approval", APPROVER),
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
