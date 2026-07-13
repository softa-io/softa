package io.softa.starter.flow.message;

import java.time.Duration;
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
import io.softa.starter.flow.runtime.engine.DelegationExpiryScheduler;
import io.softa.starter.flow.runtime.monitor.FlowMonitorService;
import io.softa.starter.flow.service.impl.FlowDebugPurgeService;

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

    static final String APPROVAL_TIMEOUT_REMIND = "flow_approval_timeout_remind";
    static final String APPROVAL_TIMEOUT_HANDLE = "flow_approval_timeout_handle";
    static final String DELEGATION_EXPIRY = "flow_delegation_expiry";
    static final String TIMER_SWEEP = "flow_timer_sweep";
    static final String INSTANCE_STUCK_SCAN = "flow_instance_stuck_scan";
    static final String DEBUG_BUNDLE_PURGE = "flow_debug_bundle_purge";

    private static final int TIMER_SWEEP_BATCH = 200;
    private static final int STUCK_SCAN_BATCH = 200;
    private static final int DEBUG_PURGE_BATCH = 100;
    private static final Duration STUCK_THRESHOLD = Duration.ofHours(24);

    @Autowired
    private ApprovalTimeoutScheduler approvalTimeoutScheduler;

    @Autowired
    private DelegationExpiryScheduler delegationExpiryScheduler;

    @Autowired(required = false)
    private FlowMonitorService flowMonitorService;

    @Autowired(required = false)
    private FlowDebugPurgeService debugPurgeService;

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
                case APPROVAL_TIMEOUT_REMIND -> approvalTimeoutScheduler.checkTimeoutReminders();
                case APPROVAL_TIMEOUT_HANDLE -> approvalTimeoutScheduler.handleTimedOutTasks();
                case DELEGATION_EXPIRY -> delegationExpiryScheduler.expireDelegations();
                case TIMER_SWEEP -> {
                    if (flowMonitorService != null) {
                        flowMonitorService.sweepDueTimers(Instant.now(), TIMER_SWEEP_BATCH);
                    }
                }
                case INSTANCE_STUCK_SCAN -> {
                    if (flowMonitorService != null) {
                        flowMonitorService.sweepStuckInstances(
                                Instant.now().minus(STUCK_THRESHOLD), STUCK_SCAN_BATCH);
                    }
                }
                case DEBUG_BUNDLE_PURGE -> {
                    if (debugPurgeService != null) {
                        debugPurgeService.purgeExpiredDebugBundles(DEBUG_PURGE_BATCH);
                    }
                }
                default -> { /* not a maintenance cron — ignore */ }
            }
        } catch (Exception e) {
            log.error("Flow maintenance cron '{}' failed: {}", message.getCronName(), e.getMessage(), e);
        }
    }
}
