package io.softa.starter.user.provisioning;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.softa.framework.base.message.AdminProvisionedMessage;

/**
 * Bridges the in-process {@link AdminProvisionedEvent} to MQ: on {@code AFTER_COMMIT} (so a rolled-back
 * admin creation never publishes), fans an {@link AdminProvisionedMessage} to Pulsar for business modules
 * to consume and attach their own per-admin record — e.g. corehr builds an {@code Employee} bound to the
 * account. The {@code :}-empty-default topic gate makes this a no-op when
 * {@code mq.topics.admin-provisioned.topic} is unconfigured (e.g. tests / apps that don't want a business
 * record built for the admin).
 */
@Slf4j
@Component
public class AdminProvisionedPublisher {

    @Value("${mq.topics.admin-provisioned.topic:}")
    private String topic;

    private final PulsarTemplate<AdminProvisionedMessage> pulsarTemplate;

    public AdminProvisionedPublisher(PulsarTemplate<AdminProvisionedMessage> pulsarTemplate) {
        this.pulsarTemplate = pulsarTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAdminProvisioned(AdminProvisionedEvent event) {
        if (topic == null || topic.isBlank()) {
            log.debug("admin-provisioned topic unconfigured; skipping MQ publish for admin userId={}",
                    event.userId());
            return;
        }
        AdminProvisionedMessage message = new AdminProvisionedMessage(
                event.tenantId(), event.userId(), event.email(), event.mobile());
        pulsarTemplate.sendAsync(topic, message).whenComplete((__, ex) -> {
            if (ex != null) {
                log.error("failed to publish admin-provisioned for admin userId={}", event.userId(), ex);
            }
        });
    }
}
