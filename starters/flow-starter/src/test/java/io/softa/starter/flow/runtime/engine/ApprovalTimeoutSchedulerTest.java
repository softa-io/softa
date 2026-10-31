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
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.StubFlowBundleRegistry;
import io.softa.starter.flow.runtime.StubFlowInstanceStore;
import io.softa.starter.flow.runtime.TestFlowRuntimeFactory;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.ApprovalTimeoutConfig;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step under the Remind strategy is reminded only once past its deadline: at the deadline, then once per
 * interval up to the most — never once per sweep — and with no interval, once.
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

    private final ApprovalTimeoutScheduler scheduler = new ApprovalTimeoutScheduler(instanceStore, bundleRegistry, engine,
            notifications, new PendingApprovalFactory(new ApproverResolutionService(),
                    new ApprovalAuditReader(new NoopApprovalActionLedger())));

    @Test
    void aStepPastItsDeadlineIsRemindedThenOncePerIntervalUpToTheMost() {
        PendingApproval step = startPastDeadline(Map.of("timeoutHours", 24, "remindIntervalHours", 4, "maxRemindTimes", 2));

        scheduler.handleTimedOutTasks();
        scheduler.handleTimedOutTasks();
        assertEquals(1, reminders.size(), "a sweep within the interval reminds nobody again");
        assertEquals(List.of(APPROVER), reminders.getFirst().pendingApproval().getApprovers());

        step.setLastRemindTime(step.getLastRemindTime().minusHours(4));
        scheduler.handleTimedOutTasks();
        assertEquals(2, reminders.size());

        step.setLastRemindTime(step.getLastRemindTime().minusHours(4));
        scheduler.handleTimedOutTasks();
        assertEquals(2, reminders.size(), "two reminders at most");
    }

    @Test
    void withNoIntervalAStepPastItsDeadlineIsRemindedOnce() {
        PendingApproval step = startPastDeadline(Map.of("timeoutHours", 24));

        scheduler.handleTimedOutTasks();
        step.setLastRemindTime(step.getLastRemindTime().minusDays(2));
        scheduler.handleTimedOutTasks();

        assertEquals(1, reminders.size());
    }

    @Test
    void aStepBeforeItsDeadlineIsNotReminded() {
        start(Map.of("timeoutHours", 24, "remindIntervalHours", 4));

        scheduler.handleTimedOutTasks();

        assertTrue(reminders.isEmpty());
    }

    @Test
    void anIntervalWithoutAMostRemindsThreeTimes() {
        ApprovalTimeoutConfig config = ApprovalTimeoutConfig.builder().timeoutHours(24).remindIntervalHours(1).build();
        LocalDateTime now = LocalDateTime.now();

        assertTrue(ApprovalTimeoutScheduler.remindsNow(
                PendingApproval.builder().remindCount(2).lastRemindTime(now.minusHours(1)).build(), config, now));
        assertFalse(ApprovalTimeoutScheduler.remindsNow(
                PendingApproval.builder().remindCount(3).lastRemindTime(now.minusHours(1)).build(), config, now));
    }

    /** The approval's only step, as the instance store holds it, past its deadline. */
    private PendingApproval startPastDeadline(Map<String, Object> timeout) {
        FlowExecutionState state = start(timeout);
        PendingApproval step = instanceStore.get(state.getInstanceId()).orElseThrow().getPendingApprovals().getFirst();
        step.setDueTime(LocalDateTime.now().minusMinutes(1));
        return step;
    }

    private FlowExecutionState start(Map<String, Object> timeout) {
        publishService.publish(FLOW_ID, DesignFlowDefinition.builder()
                .name("Timeout Scenarios")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START, null),
                                node("approval", FlowNodeType.APPROVAL,
                                        Map.of("approvers", List.of(APPROVER), "timeout", timeout)),
                                node("end", FlowNodeType.END, null)))
                        .edges(List.of(edge("e1", "start", "approval"), edge("e2", "approval", "end")))
                        .build())
                .build());
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
        start.setInitiatorId("initiator-1");
        FlowExecutionState state = engine.start(start);
        assertEquals(FlowExecutionStatus.WAITING, state.getStatus());
        return state;
    }

    private static FlowGraphNode node(String id, FlowNodeType type, Map<String, Object> config) {
        return FlowGraphNode.builder().id(id).type(type).label(id).config(config).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }
}
