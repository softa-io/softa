package io.softa.starter.tenant.provisioning;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.softa.framework.base.message.TenantProvisionedMessage;

/**
 * Bridges the in-process {@link TenantProvisionedEvent} to MQ: on {@code AFTER_COMMIT} (so a rolled-back
 * provisioning never publishes), fans a {@link TenantProvisionedMessage} to Pulsar for business modules
 * to consume and self-seed per-tenant data. The {@code :}-empty-default topic gate means this is a no-op
 * when {@code mq.topics.tenant-provisioned.topic} is unconfigured (e.g. tests / apps that don't want
 * business seeding).
 */
@Slf4j
@Component
public class TenantProvisionedPublisher {

    @Value("${mq.topics.tenant-provisioned.topic:}")
    private String topic;

    private final PulsarTemplate<TenantProvisionedMessage> pulsarTemplate;

    public TenantProvisionedPublisher(PulsarTemplate<TenantProvisionedMessage> pulsarTemplate) {
        this.pulsarTemplate = pulsarTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTenantProvisioned(TenantProvisionedEvent event) {
        if (topic == null || topic.isBlank()) {
            log.debug("tenant-provisioned topic unconfigured; skipping MQ publish for tenant {}", event.tenantId());
            return;
        }
        TenantProvisionedMessage message =
                new TenantProvisionedMessage(event.tenantId(), event.code(), event.name());
        pulsarTemplate.sendAsync(topic, message).whenComplete((__, ex) -> {
            if (ex != null) {
                log.error("failed to publish tenant-provisioned for tenant {}", event.tenantId(), ex);
            }
        });
    }
}
