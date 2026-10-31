package io.softa.starter.flow.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import io.softa.starter.flow.runtime.api.FlowCcRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.FlowParticipants;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An approver CC'ing people on their pending step (START → Approval → END, two approvers): several in one action,
 * each recorded and told; the people who already see the approval from the step skipped; the action refused when
 * nobody is left, or when the actor is not an approver still to vote.
 */
class ManualCcScenarioTest {

    private static final String FLOW_ID = "9300";
    private static final String INITIATOR = "initiator-1";
    private static final String FIRST = "manager-1";
    private static final String SECOND = "manager-2";

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService =
            new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final List<FlowNotificationEvent> notices = new ArrayList<>();

    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.createWithNotifications(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler,
            notices::add);

    @Test
    void anApproverCcsSeveralPeopleInOneAction() {
        String instanceId = start("AnyOne");

        FlowExecutionState state = engine.cc(cc(instanceId, FIRST, "cc-1", "cc-2"));

        List<ApprovalActionAuditEntry> ccs = ccsBy(state, FIRST);
        assertEquals(List.of("cc-1", "cc-2"), targets(ccs));
        assertTrue(ccs.stream().allMatch(entry -> "approval".equals(entry.getNodeId()) && entry.getCycleNumber() == 1));
        assertEquals(FlowExecutionStatus.WAITING, state.getStatus(), "a CC changes nobody's task");
        assertTrue(FlowParticipants.isParticipant(state, state.getApprovalAuditDelta(), "cc-2"));
        assertEquals(List.of(List.of("cc-1", "cc-2")),
                ccNotices().stream().map(FlowNotificationEvent.CcSent::recipientActorIds).toList(),
                "told together, in one notice");
    }

    @Test
    void peopleWhoAlreadySeeTheApprovalFromTheStepAreSkipped() {
        String instanceId = start("AnyOne");
        engine.cc(cc(instanceId, FIRST, "cc-1"));

        // cc-1 is CC'd on the step already, FIRST approves it, SECOND is the actor; cc-3 is chosen twice.
        FlowExecutionState state = engine.cc(cc(instanceId, SECOND, "cc-1", FIRST, SECOND, "cc-3", " cc-3 "));

        assertEquals(List.of("cc-3"), targets(ccsBy(state, SECOND)));
        assertEquals(List.of("cc-3"), ccNotices().getLast().recipientActorIds());
    }

    @Test
    void nobodyLeftToCcIsRefusedAndRecordsNothing() {
        String instanceId = start("AnyOne");
        engine.cc(cc(instanceId, FIRST, "cc-1"));

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> engine.cc(cc(instanceId, FIRST, "cc-1", SECOND)));

        assertTrue(refused.getMessage().startsWith("Nobody to CC"), refused.getMessage());
        assertEquals(List.of("cc-1"), targets(ccsBy(engine.getInstance(instanceId).orElseThrow(), FIRST)));
        assertThrows(FlowActionValidationException.class, () -> engine.cc(cc(instanceId, FIRST, " ", "")),
                "a choice of nobody");
    }

    @Test
    void onlyAnApproverStillToVoteMayCc() {
        String instanceId = start("Unanimous");
        assertThrows(FlowActionValidationException.class, () -> engine.cc(cc(instanceId, "outsider", "cc-1")));

        FlowApproveRequest approve = new FlowApproveRequest();
        approve.setInstanceId(instanceId);
        approve.setNodeId("approval");
        approve.setActorId(FIRST);
        assertEquals(FlowExecutionStatus.WAITING, engine.approve(approve).getStatus(), "SECOND is still to vote");

        assertThrows(FlowActionValidationException.class, () -> engine.cc(cc(instanceId, FIRST, "cc-1")));
    }

    private String start(String approvalMode) {
        publishService.publish(FLOW_ID, DesignFlowDefinition.builder()
                .name("Manual CC")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("approval")
                                        .config(Map.of("approvers", List.of(FIRST, SECOND), "approvalMode", approvalMode))
                                        .build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()))
                        .build())
                .build());
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
        start.setInitiatorId(INITIATOR);
        return engine.start(start).getInstanceId();
    }

    private static FlowCcRequest cc(String instanceId, String actorId, String... targets) {
        FlowCcRequest cc = new FlowCcRequest();
        cc.setInstanceId(instanceId);
        cc.setNodeId("approval");
        cc.setActorId(actorId);
        cc.setTargetActorIds(List.of(targets));
        cc.setComment("for your information");
        return cc;
    }

    /** The CC entries the actor recorded, oldest first. */
    private static List<ApprovalActionAuditEntry> ccsBy(FlowExecutionState state, String actorId) {
        return state.getApprovalAuditDelta() == null ? List.of() : state.getApprovalAuditDelta().stream()
                .filter(entry -> ApprovalActionType.CC.equals(entry.getAction()) && actorId.equals(entry.getActorId()))
                .toList();
    }

    private static List<String> targets(List<ApprovalActionAuditEntry> ccs) {
        return ccs.stream().map(ApprovalActionAuditEntry::getTargetActorId).toList();
    }

    private List<FlowNotificationEvent.CcSent> ccNotices() {
        return notices.stream()
                .filter(FlowNotificationEvent.CcSent.class::isInstance)
                .map(FlowNotificationEvent.CcSent.class::cast)
                .toList();
    }
}
