package io.softa.starter.flow.message;

import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.flow.runtime.engine.ApprovalTimeoutScheduler;
import io.softa.starter.flow.runtime.monitor.FlowMonitorService;
import io.softa.starter.flow.service.impl.FlowAutomationPurgeService;

/**
 * Consumes Cron task messages from Pulsar and dispatches flow maintenance jobs.
 * <p>
 * Uses an independent subscription name ({@code cron-task-maintenance-sub}) so that
 * both this consumer and {@link CronTaskFlowConsumer} receive all messages via
 * Pulsar fan-out.
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.cron-task.topic")
public class FlowMaintenanceCronConsumer {

    static final String APPROVAL_TIMEOUT_HANDLE = "flow_approval_timeout_handle";
    static final String TIMER_SWEEP = "flow_timer_sweep";
    static final String AUTOMATION_PURGE = "flow_automation_purge";

    private static final int TIMER_SWEEP_BATCH = 200;
    private static final int AUTOMATION_PURGE_BATCH = 500;
    private static final int AUTOMATION_PURGE_MAX_BATCHES = 20;

    @Autowired
    private ApprovalTimeoutScheduler approvalTimeoutScheduler;

    @Autowired(required = false)
    private FlowMonitorService flowMonitorService;

    @Autowired(required = false)
    private FlowAutomationPurgeService automationPurgeService;

    @PulsarListener(
            topics = "${mq.topics.cron-task.topic}",
            subscriptionName = "${mq.topics.cron-task.maintenance-sub:cron-task-maintenance-sub}",
            subscriptionType = SubscriptionType.Shared,
            deadLetterPolicy = "flowDeadLetterPolicy"
    )
    public void onMessage(CronTaskMessage message) {
        if (message.getCronName() == null) {
            return;
        }
        Context ctx = message.getContext();
        Runnable task = () -> dispatch(message);
        if (ctx != null) {
            ContextHolder.runWith(ctx, task);
        } else {
            task.run();
        }
    }

    private void dispatch(CronTaskMessage message) {
        try {
            switch (message.getCronName()) {
                case APPROVAL_TIMEOUT_HANDLE -> approvalTimeoutScheduler.handleTimedOutTasks();
                case TIMER_SWEEP -> {
                    if (flowMonitorService != null) {
                        flowMonitorService.sweepDueTimers(Instant.now(), TIMER_SWEEP_BATCH);
                    }
                }
                case AUTOMATION_PURGE -> {
                    if (automationPurgeService != null) {
                        automationPurgeService.purgeExpiredRuns(AUTOMATION_PURGE_BATCH, AUTOMATION_PURGE_MAX_BATCHES);
                    }
                }
                default -> { /* not a maintenance cron — ignore */ }
            }
        } catch (Exception e) {
            log.error("Flow maintenance cron '{}' failed: {}", message.getCronName(), e.getMessage(), e);
        }
    }
}
