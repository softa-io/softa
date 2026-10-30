package io.softa.starter.flow.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowCcRule;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowCcReadRequest;
import io.softa.starter.flow.runtime.api.FlowCommentRequest;
import io.softa.starter.flow.runtime.api.FlowRejectRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.engine.FlowParticipants;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;
import io.softa.starter.flow.service.support.FlowApprovalTaskProjector;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An approval flow's CC rules (part of its definition) over a single-step approval (START → Approval → END): a
 * rule's recipients are recorded like a manual CC's — a CC entry with no actor on the start node or the step that
 * ended the approval — so they get a CC task, take part in the instance (they comment on it) and mark the CC read.
 * The rules are the approval's: they fire as it is submitted, approved or rejected, not at each step.
 */
class AutoCcScenarioTest {

    private static final String FLOW_ID = "9200";
    private static final String INITIATOR = "initiator-1";
    private static final String FIRST = "manager-1";
    private static final String SECOND = "manager-2";

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(compiler, bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final List<FlowNotificationEvent> notices = new ArrayList<>();
    private final FlowApprovalTaskProjector projector =
            new FlowApprovalTaskProjector(new ApprovalAuditReader(new NoopApprovalActionLedger()));

    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.createWithNotifications(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler,
            notices::add);

    @Test
    void aRuleCcsItsRecipientsAsAManualCcWould() {
        publish("AnyOne", rule(CcTiming.ON_SUBMIT, "cc-1"));

        FlowExecutionState started = start();
        ApprovalActionAuditEntry cc = ccsOf(started).getFirst();
        assertEquals("start", cc.getNodeId());
        assertEquals(1, cc.getCycleNumber());
        assertEquals("cc-1", cc.getTargetActorId());
        assertNull(cc.getActorId(), "no person sent it");

        FlowApprovalTask task = projector.project(started).stream()
                .filter(t -> FlowApprovalTaskType.CC.equals(t.getTaskType()))
                .findFirst()
                .orElseThrow();
        assertEquals("cc-1", task.getActorId());
        assertTrue(FlowParticipants.isParticipant(started, started.getApprovalAuditDelta(), "cc-1"));
        assertEquals(List.of("cc-1"), ccNotices().getFirst().recipientActorIds());

        assertDoesNotThrow(() -> engine.addComment(comment(started, "cc-1")));
        assertDoesNotThrow(() -> engine.readCc(read(started, "start", 1, "cc-1")));
    }

    @Test
    void theApprovedRuleCcsOnceTheApprovalEndsApprovedNotAtEachApproval() {
        publish("Unanimous", rule(CcTiming.ON_APPROVED, "cc-2"));
        FlowExecutionState started = start();
        assertTrue(ccsOf(started).isEmpty(), "not approved yet");

        FlowExecutionState halfway = engine.approve(approve(started, FIRST));
        assertEquals(FlowExecutionStatus.WAITING, halfway.getStatus());
        assertTrue(ccsOf(halfway).isEmpty(), "one approver of two is not the approval ending");

        FlowExecutionState completed = engine.approve(approve(started, SECOND));
        assertEquals(FlowExecutionStatus.COMPLETED, completed.getStatus());
        List<ApprovalActionAuditEntry> ccs = ccsOf(completed);
        assertEquals(1, ccs.size());
        assertEquals("approval", ccs.getFirst().getNodeId());
        assertEquals("cc-2", ccs.getFirst().getTargetActorId());
        assertEquals(1, ccNotices().size());
    }

    @Test
    void theRejectedRuleCcsWhenTheApprovalEndsRejected() {
        publish("AnyOne", rule(CcTiming.ON_REJECTED, "cc-4"), rule(CcTiming.ON_APPROVED, "cc-never"));
        FlowExecutionState started = start();

        FlowRejectRequest reject = new FlowRejectRequest();
        reject.setInstanceId(started.getInstanceId());
        reject.setNodeId("approval");
        reject.setActorId(FIRST);
        reject.setComment("not acceptable");
        FlowExecutionState rejected = engine.reject(reject);

        assertEquals(FlowExecutionStatus.REJECTED, rejected.getStatus());
        List<ApprovalActionAuditEntry> ccs = ccsOf(rejected);
        assertEquals(List.of("cc-4"), ccs.stream().map(ApprovalActionAuditEntry::getTargetActorId).toList());
    }

    @Test
    void recipientsAreFoundTheWayAStepsApproversAre() {
        publish("AnyOne", FlowCcRule.builder().timing(CcTiming.ON_SUBMIT)
                .recipientSource(Map.of("type", "VariableList", "variable", "watchers")).build());
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
        start.setInitiatorId(INITIATOR);
        start.setVariables(Map.of("watchers", List.of("cc-5", "cc-6")));

        FlowExecutionState started = engine.start(start);

        assertEquals(List.of("cc-5", "cc-6"),
                ccsOf(started).stream().map(ApprovalActionAuditEntry::getTargetActorId).toList());
    }

    @Test
    void aFlowWithoutRulesCcsNobody() {
        publish("AnyOne");
        FlowExecutionState completed = engine.approve(approve(start(), FIRST));

        assertEquals(FlowExecutionStatus.COMPLETED, completed.getStatus());
        assertTrue(ccsOf(completed).isEmpty());
        assertFalse(notices.stream().anyMatch(FlowNotificationEvent.CcSent.class::isInstance));
    }

    private void publish(String approvalMode, FlowCcRule... rules) {
        publishService.publish(FLOW_ID, DesignFlowDefinition.builder()
                .name("Auto CC")
                .scenario(FlowScenario.APPROVAL)
                .modelName("LeaveRequest").trigger(new SubmitTrigger())
                .cc(List.of(rules))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of("approvers", List.of(FIRST, SECOND), "approvalMode", approvalMode))
                                        .build(),
                                node("end", FlowNodeType.END)))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")))
                        .build())
                .build());
    }

    private FlowExecutionState start() {
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
        start.setInitiatorId(INITIATOR);
        return engine.start(start);
    }

    private static FlowCcRule rule(CcTiming timing, String recipient) {
        return FlowCcRule.builder().timing(timing).recipients(List.of(recipient)).build();
    }

    private static FlowApproveRequest approve(FlowExecutionState state, String actorId) {
        FlowApproveRequest approve = new FlowApproveRequest();
        approve.setInstanceId(state.getInstanceId());
        approve.setNodeId("approval");
        approve.setActorId(actorId);
        return approve;
    }

    private static FlowCommentRequest comment(FlowExecutionState state, String actorId) {
        FlowCommentRequest comment = new FlowCommentRequest();
        comment.setInstanceId(state.getInstanceId());
        comment.setActorId(actorId);
        comment.setComment("noted");
        return comment;
    }

    private static FlowCcReadRequest read(FlowExecutionState state, String nodeId, int cycle, String actorId) {
        FlowCcReadRequest read = new FlowCcReadRequest();
        read.setInstanceId(state.getInstanceId());
        read.setNodeId(nodeId);
        read.setCycleNumber(cycle);
        read.setActorId(actorId);
        return read;
    }

    private static List<ApprovalActionAuditEntry> ccsOf(FlowExecutionState state) {
        return state.getApprovalAuditDelta() == null ? List.of() : state.getApprovalAuditDelta().stream()
                .filter(entry -> ApprovalActionType.CC.equals(entry.getAction()))
                .toList();
    }

    private List<FlowNotificationEvent.CcSent> ccNotices() {
        return notices.stream()
                .filter(FlowNotificationEvent.CcSent.class::isInstance)
                .map(FlowNotificationEvent.CcSent.class::cast)
                .toList();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }
}
