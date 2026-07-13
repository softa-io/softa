package io.softa.starter.message.mq;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Maps {@link TopicRoute} values onto physical broker topics and default
 * subscription names.
 * <p>
 * Sample YAML:
 * <pre>
 * mq:
 *   topics:
 *     mail-send:
 *       topic: persistent://public/default/mail-send
 *       sub: mail-send-sub
 *     sms-send:     { topic: ..., sub: ... }
 * </pre>
 * Initial attempts and delayed retries share the same per-channel topic. Retry
 * timing is represented by the outbox row's {@code nextAttemptAt}; the broker
 * route does not encode attempt type.
 * When a route is not configured, {@link MqProducer#isAvailable(TopicRoute)}
 * returns {@code false} and the outbox publisher keeps the entry queued
 * until an operator supplies a topic.
 */
@Data
@ConfigurationProperties(prefix = "mq")
public class MqTopicsProperties {

    private Map<String, TopicConfig> topics = new HashMap<>();

    public TopicConfig get(TopicRoute route) {
        return topics.get(route.name().toLowerCase().replace('_', '-'));
    }

    @Data
    public static class TopicConfig {
        /** Physical broker topic name (e.g. {@code persistent://.../mail-send}). */
        private String topic;
        /** Default subscription name used by consumers. */
        private String sub;
    }
}
