package io.softa.starter.flow.runtime.engine;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.runtime.store.FlowInstanceStore;

/**
 * Reminds the approvers of the steps that are due: an approval flow's {@code remindAfterHours} after a step opens, its
 * approvers still to act are reminded, then again every as many hours, at most {@value #MAX_REMINDERS} times. A
 * reminder is all that happens — nothing is decided for anyone: a step no one acts on waits, and its approver can
 * delegate, its submitter urge, an administrator reassign. Nothing is sent before a step is due. The job runs on the
 * hour by default, so a step is reminded within the hour after it falls due: its timing is counted in hours.
 */
@Slf4j
@Component
public class ApprovalTimeoutScheduler {

    /** The most reminders a step gets. */
    static final int MAX_REMINDERS = 3;

    /**
     * How much earlier than a step's next reminder a run may come and still send it. A reminder is stamped with the
     * moment its run started, a little past the hour; the run an interval later can start a little less past it, and
     * would leave the reminder to the run after — an hourly reminder every two hours.
     */
    static final Duration REMIND_EARLY_BY = Duration.ofMinutes(5);

    private final FlowInstanceStore instanceStore;
    private final FlowBundleRegistry bundleRegistry;
    private final ApprovalNotificationService notificationService;

    public ApprovalTimeoutScheduler(FlowInstanceStore instanceStore, FlowBundleRegistry bundleRegistry,
                                    ApprovalNotificationService notificationService) {
        this.instanceStore = instanceStore;
        this.bundleRegistry = bundleRegistry;
        this.notificationService = notificationService;
    }

    /**
     * Remind the approvers of the steps that are due. Triggered via cron-starter (flow_approval_timeout_handle), once
     * per tenant, in the tenant's context: the reminders and the instances they update are the tenant's. One instance
     * that cannot be reminded leaves the others to go on, and is tried again on the next run.
     */
    public void handleTimedOutTasks() {
        List<FlowExecutionState> waitingStates = instanceStore.listByStatus(FlowExecutionStatus.WAITING);
        LocalDateTime now = LocalDateTime.now();
        for (FlowExecutionState state : waitingStates) {
            try {
                remindDueSteps(state, now);
            } catch (Exception e) {
                log.error("Error reminding the approvers of flow instance {}: {}", state.getInstanceId(),
                        e.getMessage(), e);
            }
        }
    }

    private void remindDueSteps(FlowExecutionState state, LocalDateTime now) {
        Integer interval = bundleRegistry.getByBundleId(state.getBundleId())
                .map(CompiledFlowDefinition::getRemindAfterHours)
                .orElse(null);
        List<PendingApproval> reminded = state.getPendingApprovals().stream()
                .filter(step -> step.getDueTime() != null && !step.getDueTime().isAfter(now))
                .filter(step -> remindsNow(step, interval, now))
                .toList();
        if (reminded.isEmpty()) {
            return;
        }
        for (PendingApproval step : reminded) {
            step.setRemindCount((step.getRemindCount() == null ? 0 : step.getRemindCount()) + 1);
            step.setLastRemindTime(now);
        }
        instanceStore.save(state);
        for (PendingApproval step : reminded) {
            notificationService.notify(new FlowNotificationEvent.TimeoutReminded(state, step, step.getRemindCount()));
            log.info("Sent overdue reminder {} for instance {}, node {}", step.getRemindCount(), state.getInstanceId(),
                    step.getNodeId());
        }
    }

    /**
     * Whether a due step is reminded now: first when it falls due; then once per {@code intervalHours} since the last
     * reminder — counted from {@link #REMIND_EARLY_BY} before, so the run on the hour sends it — until
     * {@value #MAX_REMINDERS} are sent. Without an interval — a step due by a setting its revision no longer has —
     * once.
     */
    static boolean remindsNow(PendingApproval step, Integer intervalHours, LocalDateTime now) {
        int sent = step.getRemindCount() == null ? 0 : step.getRemindCount();
        if (intervalHours == null || intervalHours < 1) {
            return sent == 0;
        }
        LocalDateTime last = step.getLastRemindTime();
        return sent < MAX_REMINDERS
                && (last == null || !now.isBefore(last.plusHours(intervalHours).minus(REMIND_EARLY_BY)));
    }
}
