package io.softa.starter.tenant.provisioning;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.message.SeederCompletedMessage;
import io.softa.starter.tenant.service.impl.TenantProvisioningStatusService;

/**
 * tenant-starter's subscriber to {@link SeederCompletedMessage}: folds each seeder's completion into the
 * tenant's provisioning status via {@link TenantProvisioningStatusService}. Business-agnostic — forwards
 * the opaque {@code seederKey} straight through. Gated by {@code mq.topics.seeder-completed.topic} (bean
 * absent when unconfigured). Shared subscription so it scales across app instances; the status service is
 * idempotent (upsert + set-containment), so redelivery is harmless.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.seeder-completed.topic")
public class SeederCompletedCoordinator {

    private final TenantProvisioningStatusService statusService;

    public SeederCompletedCoordinator(TenantProvisioningStatusService statusService) {
        this.statusService = statusService;
    }

    @PulsarListener(topics = "${mq.topics.seeder-completed.topic}",
            subscriptionName = "${mq.topics.seeder-completed.sub:seeder-completed-coordinator}",
            subscriptionType = SubscriptionType.Shared)
    public void onSeederCompleted(SeederCompletedMessage message) {
        if (message == null || message.tenantId() == null || message.seederKey() == null) {
            return;
        }
        if (message.success()) {
            statusService.markSeederReady(message.tenantId(), message.seederKey());
        } else {
            // Unreachable today: seeders publish only success=true (a seed failure is retried via redelivery,
            // never reported as false). Retained for a future DLQ handler that reports success=false; the
            // authoritative FAILED source meanwhile is the timeout guard (failTimedOut). See markSeederFailed.
            statusService.markSeederFailed(message.tenantId(), message.seederKey());
        }
    }
}
