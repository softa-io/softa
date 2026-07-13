package io.softa.starter.message.mq.pulsar;

import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mq.MqProducer;
import io.softa.starter.message.mq.MqTopicsProperties;
import io.softa.starter.message.mq.TopicRoute;

/**
 * {@link MqProducer} implementation backed by Spring's {@link PulsarTemplate}.
 * <p>
 * This is the only class in the code base that knows Pulsar's API surface.
 * Consumers and producers deal in {@link TopicRoute} enums and {@link Object}
 * payloads; everything else is resolved here.
 */
@Slf4j
@Component
public class PulsarMqProducer implements MqProducer {

    private final MqTopicsProperties topics;

    @Autowired(required = false)
    private PulsarTemplate<Object> pulsarTemplate;

    public PulsarMqProducer(MqTopicsProperties topics) {
        this.topics = topics;
    }

    @Override
    public boolean isAvailable(TopicRoute route) {
        if (pulsarTemplate == null) return false;
        MqTopicsProperties.TopicConfig cfg = topics.get(route);
        return cfg != null && StringUtils.isNotBlank(cfg.getTopic());
    }

    @Override
    public CompletableFuture<Void> sendAsync(TopicRoute route, Object payload) {
        String topic = resolveTopicOrThrow(route);
        return pulsarTemplate.newMessage(payload)
                .withTopic(topic)
                .sendAsync()
                .thenApply(id -> null);
    }

    private String resolveTopicOrThrow(TopicRoute route) {
        if (pulsarTemplate == null) {
            throw new IllegalStateException(
                    "PulsarTemplate is not available; cannot publish to route " + route);
        }
        MqTopicsProperties.TopicConfig cfg = topics.get(route);
        if (cfg == null || StringUtils.isBlank(cfg.getTopic())) {
            throw new IllegalStateException("mq.topics." + route.name().toLowerCase()
                    .replace('_', '-') + ".topic is not configured");
        }
        return cfg.getTopic();
    }
}
