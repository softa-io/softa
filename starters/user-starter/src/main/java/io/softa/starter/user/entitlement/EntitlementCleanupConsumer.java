package io.softa.starter.user.entitlement;

import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import static io.softa.framework.base.context.ContextUtils.inTenantContext;
import io.softa.framework.base.message.EntitlementChangeMessage;

/**
 * Consumes {@link EntitlementChangeMessage} and cleans up the tenant's over-plan role grants
 * (v2 §2.2). Registered only when {@code mq.topics.entitlement-change.topic} is configured
 * (Pulsar optional). Runs the cleanup under a context scoped to the message's tenant (so
 * tenant-filtered reads/deletes target the right tenant) with permission checks skipped
 * (system cleanup, not a user query).
 *
 * <p>The subscription is Shared, so every replica takes messages off the topic; under the former
 * default (Exclusive) a second replica failed to boot. Two changes for one tenant may now be
 * cleaned up out of order; a cleanup only deletes, so both orders remove the same grants.
 * Switching an existing deployment from Exclusive needs one stop-then-start release: Pulsar
 * refuses a Shared consumer while an Exclusive one is still attached.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.entitlement-change.topic")
public class EntitlementCleanupConsumer {

    private final EntitlementRoleCleanupService cleanupService;

    public EntitlementCleanupConsumer(EntitlementRoleCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @PulsarListener(topics = "${mq.topics.entitlement-change.topic}",
            subscriptionName = "${mq.topics.entitlement-change.user-sub:entitlement-cleanup-user}",
            subscriptionType = SubscriptionType.Shared)
    public void onMessage(EntitlementChangeMessage message) {
        if (message == null || message.tenantId() == null) {
            return;
        }
        inTenantContext(message.tenantId(),
                () -> cleanupService.cleanup(message.tenantId(), message.entitledModules()));
    }
}
