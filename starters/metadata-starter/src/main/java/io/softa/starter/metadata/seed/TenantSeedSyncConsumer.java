package io.softa.starter.metadata.seed;

import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

/**
 * Runs the tenant tasks of seed sync batches. Shared subscription: every instance takes a share.
 *
 * <p>A task that fails is recorded as failed and not thrown back: redelivering it would fail the same way.
 * Only a message the instance never finished — it stopped mid-task — comes back, and the task picks up
 * where the database says it is.
 */
@Component
@ConditionalOnProperty(name = "mq.topics.seed-tenant-sync.topic")
public class TenantSeedSyncConsumer {

    private final TenantSeedSyncRunner runner;

    public TenantSeedSyncConsumer(TenantSeedSyncRunner runner) {
        this.runner = runner;
    }

    @PulsarListener(topics = "${mq.topics.seed-tenant-sync.topic}",
            subscriptionName = "${mq.topics.seed-tenant-sync.sub:seed-tenant-sync-sub}",
            subscriptionType = SubscriptionType.Shared,
            // Tenants are independent — each is its own transaction — so an instance runs several at once.
            concurrency = "${mq.topics.seed-tenant-sync.concurrency:4}",
            deadLetterPolicy = "seedTenantSyncRetryPolicy")
    public void onMessage(TenantSeedSyncMessage message) {
        if (message != null && message.taskId() != null) {
            runner.runTask(message.taskId());
        }
    }
}
