package io.softa.starter.flow.runtime.engine;

import java.time.LocalDateTime;
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
import io.softa.starter.flow.runtime.StubFlowBundleRegistry;
import io.softa.starter.flow.runtime.StubFlowInstanceStore;
import io.softa.starter.flow.runtime.TestFlowRuntimeFactory;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An approval flow's {@code remindAfterHours} makes each step due that many hours after it opens: its approvers still
 * to act are reminded then, and again every as many hours, three times at most. A reminder is all that happens —
 * the step goes on waiting for its approvers.
 */
class ApprovalTimeoutSchedulerTest {

    private static final String FLOW_ID = "9300";
    private static final String APPROVER = "manager-1";

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final StubFlowInstanceStore instanceStore = new StubFlowInstanceStore();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();

    private final List<FlowNotificationEvent.TimeoutReminded> reminders = new ArrayList<>();
    private final ApprovalNotificationService notifications = event -> {
        if (event instanceof FlowNotificationEvent.TimeoutReminded reminded) {
            reminders.add(reminded);
        }
    };

    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.createWithNotifications(bundleRegistry,
            instanceStore, new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler, notifications);

    private final ApprovalTimeoutScheduler scheduler =
            new ApprovalTimeoutScheduler(instanceStore, bundleRegistry, notifications);

    @Test
    void aStepIsDueItsFlowsHoursAfterItOpens() {
        LocalDateTime before = LocalDateTime.now();

        PendingApproval step = stepOf(start(24));

        assertFalse(step.getDueTime().isBefore(before.plusHours(24)));
        assertTrue(step.getDueTime().isBefore(LocalDateTime.now().plusHours(24).plusMinutes(1)));
    }

    @Test
    void withoutTheSettingAStepIsNeverDue() {
        assertNull(stepOf(start(null)).getDueTime());
    }

    @Test
    void aDueStepIsRemindedThenOncePerIntervalThreeTimesAtMost() {
        FlowExecutionState state = start(4);
        PendingApproval step = stepOf(state);
        step.setDueTime(LocalDateTime.now().minusMinutes(1));

        scheduler.handleTimedOutTasks();
        scheduler.handleTimedOutTasks();
        assertEquals(1, reminders.size(), "a run within the interval reminds nobody again");
        assertEquals(List.of(APPROVER), reminders.getFirst().pendingApproval().getApprovers());

        for (int sent = 2; sent <= 4; sent++) {
            step.setLastRemindTime(step.getLastRemindTime().minusHours(4));
            scheduler.handleTimedOutTasks();
        }

        assertEquals(3, reminders.size(), "three reminders at most");
        assertEquals(FlowExecutionStatus.WAITING, state.getStatus(), "a reminder decides nothing");
        assertEquals(List.of(APPROVER), stepOf(state).getApprovers());
    }

    @Test
    void aStepNotYetDueIsNotReminded() {
        start(24);

        scheduler.handleTimedOutTasks();

        assertTrue(reminders.isEmpty());
    }

    @Test
    void aStepDueByASettingItsRevisionNoLongerHasIsRemindedOnce() {
        // A step opened under a step-level timeout the engine no longer reads: due, with no interval to repeat by.
        FlowExecutionState state = start(null);
        PendingApproval step = stepOf(state);
        step.setDueTime(LocalDateTime.now().minusMinutes(1));

        scheduler.handleTimedOutTasks();
        step.setLastRemindTime(step.getLastRemindTime().minusDays(2));
        scheduler.handleTimedOutTasks();

        assertEquals(1, reminders.size());
    }

    @Test
    void remindsNowFollowsTheIntervalAndTheMost() {
        LocalDateTime now = LocalDateTime.now();

        assertTrue(ApprovalTimeoutScheduler.remindsNow(PendingApproval.builder().remindCount(0).build(), 4, now));
        assertFalse(ApprovalTimeoutScheduler.remindsNow(
                PendingApproval.builder().remindCount(1).lastRemindTime(now.minusHours(3)).build(), 4, now));
        assertTrue(ApprovalTimeoutScheduler.remindsNow(
                PendingApproval.builder().remindCount(2).lastRemindTime(now.minusHours(4)).build(), 4, now));
        assertFalse(ApprovalTimeoutScheduler.remindsNow(
                PendingApproval.builder().remindCount(3).lastRemindTime(now.minusHours(4)).build(), 4, now));
    }

    /** The approval's only step, as the instance store holds it. */
    private PendingApproval stepOf(FlowExecutionState started) {
        return instanceStore.get(started.getInstanceId()).orElseThrow().getPendingApprovals().getFirst();
    }

    private FlowExecutionState start(Integer remindAfterHours) {
        publishService.publish(FLOW_ID, DesignFlowDefinition.builder()
                .name("Timeout Scenarios")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .remindAfterHours(remindAfterHours)
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START, null),
                                node("approval", FlowNodeType.APPROVAL, Map.of("approverSources", List.of(Map.of("type", "Users", "userIds", List.of(APPROVER))))),
                                node("end", FlowNodeType.END, null)))
                        .edges(List.of(edge("e1", "start", "approval"), edge("e2", "approval", "end")))
                        .build())
                .build());
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
        start.setInitiatorId("initiator-1");
        FlowExecutionState state = engine.start(start);
        assertEquals(FlowExecutionStatus.WAITING, state.getStatus());
        return instanceStore.get(state.getInstanceId()).orElseThrow();
    }

    private static FlowGraphNode node(String id, FlowNodeType type, Map<String, Object> config) {
        return FlowGraphNode.builder().id(id).type(type).label(id).config(config).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }
}
