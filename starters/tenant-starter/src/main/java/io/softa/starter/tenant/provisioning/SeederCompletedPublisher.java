package io.softa.starter.tenant.provisioning;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

import io.softa.framework.base.message.SeederCompletedMessage;

/**
 * Publishes a {@link SeederCompletedMessage} when a seeder finishes seeding one tenant. Business seeders
 * call {@link #publish} instead of touching Pulsar directly — keeping the topic name and payload shape in
 * one place. Mirrors the {@code TenantProvisionedPublisher} topic-gate: the {@code :}-empty default makes
 * this a no-op when {@code mq.topics.seeder-completed.topic} is unconfigured (single-tenant / no-MQ).
 */
@Slf4j
@Component
public class SeederCompletedPublisher {

    @Value("${mq.topics.seeder-completed.topic:}")
    private String topic;

    private final PulsarTemplate<SeederCompletedMessage> pulsarTemplate;

    public SeederCompletedPublisher(PulsarTemplate<SeederCompletedMessage> pulsarTemplate) {
        this.pulsarTemplate = pulsarTemplate;
    }

    public void publish(Long tenantId, String seederKey, boolean success) {
        if (topic == null || topic.isBlank()) {
            log.debug("seeder-completed topic unconfigured; skipping publish for tenant {} seeder {}",
                    tenantId, seederKey);
            return;
        }
        SeederCompletedMessage message = new SeederCompletedMessage(tenantId, seederKey, success);
        pulsarTemplate.sendAsync(topic, message).whenComplete((__, ex) -> {
            if (ex != null) {
                log.error("failed to publish seeder-completed for tenant {} seeder {}", tenantId, seederKey, ex);
            }
        });
    }
}
