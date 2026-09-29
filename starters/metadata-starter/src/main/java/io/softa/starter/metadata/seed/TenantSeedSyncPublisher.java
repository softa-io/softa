package io.softa.starter.metadata.seed;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

/**
 * Hands each tenant of a batch to the MQ, so tenants are brought up to date in parallel across instances
 * and one tenant's failure or slowness holds up no other. Without the topic configured, the caller runs the
 * tenants itself, one after another.
 */
@Slf4j
@Component
public class TenantSeedSyncPublisher {

    private final String topic;
    private final ObjectProvider<PulsarTemplate<TenantSeedSyncMessage>> pulsarTemplate;

    public TenantSeedSyncPublisher(@Value("${mq.topics.seed-tenant-sync.topic:}") String topic,
                                   ObjectProvider<PulsarTemplate<TenantSeedSyncMessage>> pulsarTemplate) {
        this.topic = topic;
        this.pulsarTemplate = pulsarTemplate;
    }

    public boolean enabled() {
        return StringUtils.isNotBlank(topic) && pulsarTemplate.getIfAvailable() != null;
    }

    public void publish(TenantSeedSyncMessage message) {
        pulsarTemplate.getObject().sendAsync(topic, message).whenComplete((_, ex) -> {
            if (ex != null) {
                // The task stays Pending; a retry of the batch picks it up once it is marked failed.
                log.error("Could not publish seed sync task {} for tenant {}", message.taskId(), message.tenantId(), ex);
            }
        });
    }
}
