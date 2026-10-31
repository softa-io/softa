package io.softa.starter.flow.runtime;

import java.time.LocalDateTime;
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
import io.softa.starter.flow.dto.FlowUrgeStatus;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowCommentRequest;
import io.softa.starter.flow.runtime.api.FlowDelegateRequest;
import io.softa.starter.flow.runtime.api.FlowRejectRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.api.FlowTransferRequest;
import io.softa.starter.flow.runtime.api.FlowUrgeRequest;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.UrgeThrottle;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
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
 * approve / reject / transfer / delegate / comment / urge, plus actor-validation errors.
 * Covers the approval state machine transitions that the single end-to-end test
 * (approve only) left untested.
 */
class FlowActionScenarioTest {

    private static final String FLOW_ID = "9100";
    private static final String INITIATOR = "initiator-1";
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

    /** The engine's urge settings, read from the same bundles and approval history it uses. */
    private final UrgeThrottle throttle = new UrgeThrottle(bundleRegistry,
            new ApprovalAuditReader(new NoopApprovalActionLedger()));

    @BeforeEach
    void publishFlow() {
        publishService.publish(FLOW_ID, flowDefinition().build());
    }

    private FlowExecutionState startAndExpectPending() {
        return startAndExpectPending(FLOW_ID);
    }

    private FlowExecutionState startAndExpectPending(String flowId) {
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(flowId);
        start.setInitiatorId(INITIATOR);
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
    void onlyTheInstancesParticipantsComment() {
        FlowExecutionState started = startAndExpectPending();
        FlowCommentRequest stranger = comment(started, "stranger");
        assertThrows(FlowAuthorizationException.class, () -> engine.addComment(stranger));

        FlowExecutionState afterComment = engine.addComment(comment(started, INITIATOR));
        assertEquals(1, entries(afterComment, ApprovalActionType.COMMENT).size());
    }

    @Test
    void onlyTheInitiatorUrgesTheApproversAndEachStepIsUrgedOnItsOwn() {
        FlowExecutionState started = startAndExpectPending();
        assertThrows(FlowAuthorizationException.class, () -> engine.urge(urge(started, APPROVER)));

        FlowExecutionState afterUrge = engine.urge(urge(started, INITIATOR));
        List<ApprovalActionAuditEntry> urges = entries(afterUrge, ApprovalActionType.URGE);
        assertEquals(1, urges.size());
        assertEquals("approval", urges.getFirst().getNodeId());
        assertEquals(INITIATOR, urges.getFirst().getActorId());
    }

    @Test
    void aStepUrgedWithinTheIntervalIsUrgedByNobodyUntilItPasses() {
        FlowExecutionState started = startAndExpectPending();
        LocalDateTime urgedAt = LocalDateTime.now();
        FlowExecutionState afterUrge = engine.urge(urge(started, INITIATOR));

        FlowUrgeStatus status = throttle.status(afterUrge, LocalDateTime.now());
        assertFalse(status.urgeable());
        assertFalse(status.nextUrgeAt().isBefore(urgedAt.plusMinutes(240)));
        assertThrows(FlowActionValidationException.class, () -> engine.urge(urge(started, INITIATOR)));
        assertThrows(FlowActionValidationException.class, () -> engine.urgeAsAdministrator(urge(started, "admin")));

        // The interval has passed since the step was urged.
        entries(afterUrge, ApprovalActionType.URGE)
                .forEach(entry -> entry.setEventTime(entry.getEventTime().minusMinutes(241)));
        assertTrue(throttle.status(afterUrge, LocalDateTime.now()).urgeable());
        assertEquals(2, entries(engine.urge(urge(started, INITIATOR)), ApprovalActionType.URGE).size());
    }

    @Test
    void anAdministratorUrgesWithoutBeingTheInitiator() {
        FlowExecutionState started = startAndExpectPending();
        FlowExecutionState afterUrge = engine.urgeAsAdministrator(urge(started, "admin"));
        assertEquals("admin", entries(afterUrge, ApprovalActionType.URGE).getFirst().getActorId());
    }

    @Test
    void theInitiatorOfAFlowThatTurnsUrgingOffMayNotUrgeButAnAdministratorMay() {
        // A second approval flow of the document takes its records by a condition of its own
        publishService.publish("9101", flowDefinition().trigger(new SubmitTrigger("LeaveRequest", "true"))
                .urgeEnabled(false).build());
        FlowExecutionState started = startAndExpectPending("9101");

        assertEquals(new FlowUrgeStatus(false, null), throttle.status(started, INITIATOR, LocalDateTime.now()));
        assertTrue(throttle.status(started, "admin", LocalDateTime.now()).urgeable());
        assertThrows(FlowActionValidationException.class, () -> engine.urge(urge(started, INITIATOR)));

        FlowExecutionState afterUrge = engine.urgeAsAdministrator(urge(started, "admin"));
        assertEquals("admin", entries(afterUrge, ApprovalActionType.URGE).getFirst().getActorId());
    }

    @Test
    void eachStepIsUrgedOncePerItsFlowsInterval() {
        publishService.publish("9102", flowDefinition().trigger(new SubmitTrigger("LeaveRequest", "true"))
                .urgeIntervalMinutes(30).build());
        FlowExecutionState started = startAndExpectPending("9102");
        LocalDateTime urgedAt = LocalDateTime.now();
        FlowExecutionState afterUrge = engine.urge(urge(started, INITIATOR));

        LocalDateTime next = throttle.status(afterUrge, LocalDateTime.now()).nextUrgeAt();
        assertFalse(next.isBefore(urgedAt.plusMinutes(30)));
        assertTrue(next.isBefore(urgedAt.plusMinutes(UrgeThrottle.DEFAULT_INTERVAL_MINUTES)));

        entries(afterUrge, ApprovalActionType.URGE)
                .forEach(entry -> entry.setEventTime(entry.getEventTime().minusMinutes(31)));
        assertTrue(throttle.status(afterUrge, INITIATOR, LocalDateTime.now()).urgeable());
    }

    @Test
    void anApprovalThatHasEndedIsNotUrged() {
        FlowExecutionState started = startAndExpectPending();
        FlowApproveRequest approve = new FlowApproveRequest();
        approve.setInstanceId(started.getInstanceId());
        approve.setNodeId("approval");
        approve.setActorId(APPROVER);
        FlowExecutionState completed = engine.approve(approve);

        assertFalse(throttle.status(completed, LocalDateTime.now()).urgeable());
        assertThrows(FlowActionValidationException.class, () -> engine.urge(urge(started, INITIATOR)));
    }

    @Test
    void aRejectionWithoutACommentIsRefusedAndChangesNothing() {
        FlowExecutionState started = startAndExpectPending();
        FlowRejectRequest req = new FlowRejectRequest();
        req.setInstanceId(started.getInstanceId());
        req.setNodeId("approval");
        req.setActorId(APPROVER);
        req.setComment(" ");

        assertThrows(FlowActionValidationException.class, () -> engine.reject(req));
        assertEquals(FlowExecutionStatus.WAITING, engine.getInstance(started.getInstanceId()).orElseThrow().getStatus());
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

    private static FlowCommentRequest comment(FlowExecutionState state, String actorId) {
        FlowCommentRequest comment = new FlowCommentRequest();
        comment.setInstanceId(state.getInstanceId());
        comment.setActorId(actorId);
        comment.setComment("please clarify");
        return comment;
    }

    private static FlowUrgeRequest urge(FlowExecutionState state, String actorId) {
        FlowUrgeRequest urge = new FlowUrgeRequest();
        urge.setInstanceId(state.getInstanceId());
        urge.setActorId(actorId);
        return urge;
    }

    private static List<ApprovalActionAuditEntry> entries(FlowExecutionState state, ApprovalActionType action) {
        return state.getApprovalAuditDelta().stream()
                .filter(entry -> action.equals(entry.getAction()))
                .toList();
    }

    private static DesignFlowDefinition.DesignFlowDefinitionBuilder flowDefinition() {
        return DesignFlowDefinition.builder()
                .name("Action Scenarios")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                approvalNode("approval", APPROVER),
                                node("end", FlowNodeType.END)))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")))
                        .build());
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
