package io.softa.starter.metadata.seed;

import java.util.List;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.message.EntitlementChangeMessage;
import io.softa.starter.metadata.enums.SeedSyncTriggerType;

/**
 * When a tenant's plan changes, gives it the tenant files of the packages it is now entitled to and never
 * had. Its own subscription on the entitlement-change topic, beside the others that react to it.
 *
 * <p>A downgrade adds nothing and removes nothing: what the tenant has stays, hidden by its entitlement, and
 * keeps receiving what releases add to it.
 */
@Component
@ConditionalOnProperty(name = "mq.topics.entitlement-change.topic")
public class EntitlementChangeSeedConsumer {

    private final SeedSyncService seedSyncService;

    public EntitlementChangeSeedConsumer(SeedSyncService seedSyncService) {
        this.seedSyncService = seedSyncService;
    }

    @PulsarListener(topics = "${mq.topics.entitlement-change.topic}",
            subscriptionName = "${mq.topics.entitlement-change.seed-sub:seed-entitlement-change-sub}",
            subscriptionType = SubscriptionType.Shared,
            deadLetterPolicy = "seedTenantSyncRetryPolicy")
    public void onMessage(EntitlementChangeMessage message) {
        if (message != null && message.tenantId() != null) {
            seedSyncService.reconcile(List.of(message.tenantId()), SeedSyncTriggerType.PLAN_CHANGE);
        }
    }
}
