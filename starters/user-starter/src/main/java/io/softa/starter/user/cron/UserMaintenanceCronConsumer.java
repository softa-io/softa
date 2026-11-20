package io.softa.starter.user.cron;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.user.service.DynamicRoleSyncJob;

/**
 * user-starter's own thin cron consumer for the <b>dynamic-role membership sync</b>. Subscribes to the shared
 * {@code cron-task} broadcast under an independent subscription (Pulsar fan-out — coexists with any other
 * module's cron consumer) and handles only {@code DynamicRoleSync}; any other cron name is ignored. The job
 * ({@link DynamicRoleSyncJob}) and the Role / UserRoleRel entities live in user-starter, so its cron trigger
 * belongs here too — not as a corehr {@code CronTaskHandler} bridge.
 *
 * <p><b>PER_TENANT, not CrossTenant.</b> {@link DynamicRoleSyncJob#syncAll()} is single-tenant by contract
 * (it asserts an active {@code Context.tenantId}). So the {@code DynamicRoleSync} sys_cron row is seeded
 * {@code PER_TENANT}: the cron scheduler fans out one message per active tenant, each carrying its
 * {@code tenantId} in the message context, and we run {@code syncAll()} under that context. (The retired
 * corehr handler paired {@code CrossTenant} with a bare {@code syncAll()} — the assert tripped every tick and
 * the sync never actually ran; PER_TENANT fixes that.)
 *
 * <p>Gated by {@code mq.topics.cron-task.topic}.
 */
@Slf4j
@Component
// Optional cron-starter integration: only wires up when cron-starter is on the classpath (CronTaskMessage
// present) AND the cron-task topic is configured. A deployment using a different scheduler simply omits
// cron-starter — this consumer stays dormant and the app drives DynamicRoleSyncJob.syncAll() itself.
@ConditionalOnClass(name = "io.softa.starter.cron.message.dto.CronTaskMessage")
@ConditionalOnProperty(name = "mq.topics.cron-task.topic")
public class UserMaintenanceCronConsumer {

    /** Must equal the {@code sys_cron.name} in user-starter's data-system/SysCron.UserMaintenance.json. */
    static final String DYNAMIC_ROLE_SYNC = "DynamicRoleSync";

    private final DynamicRoleSyncJob dynamicRoleSyncJob;

    public UserMaintenanceCronConsumer(DynamicRoleSyncJob dynamicRoleSyncJob) {
        this.dynamicRoleSyncJob = dynamicRoleSyncJob;
    }

    @PulsarListener(topics = "${mq.topics.cron-task.topic}",
            subscriptionName = "${mq.topics.cron-task.user-sub:cron-task-user-sub}",
            subscriptionType = SubscriptionType.Shared)
    public void onMessage(CronTaskMessage message) {
        if (message == null || message.getCronName() == null) {
            return;
        }
        // PER_TENANT: the scheduler put this tenant's id on the message context; run under it so
        // syncAll()'s active-tenant assertion holds.
        Context ctx = message.getContext();
        Runnable task = () -> dispatch(message.getCronName());
        if (ctx != null) {
            ContextHolder.runWith(ctx, task);
        } else {
            task.run();
        }
    }

    private void dispatch(String cronName) {
        try {
            if (DYNAMIC_ROLE_SYNC.equals(cronName)) {
                long startMs = System.currentTimeMillis();
                dynamicRoleSyncJob.syncAll();
                log.info("[CRON] {} — dynamic-role sync finished in {}ms",
                        DYNAMIC_ROLE_SYNC, System.currentTimeMillis() - startMs);
            }
            // Any other cronName on the shared topic belongs to another module — not ours, ignore.
        } catch (Exception e) {
            // Swallow: rethrowing on a Shared subscription would cause a tight NACK-redelivery loop. Log for
            // alerting; the next tick retries. Users on dynamic roles keep their PRIOR set until a success.
            log.error("[CRON_FAILURE] {} — dynamic-role sync failed: {}", cronName, e.getMessage(), e);
        }
    }
}
