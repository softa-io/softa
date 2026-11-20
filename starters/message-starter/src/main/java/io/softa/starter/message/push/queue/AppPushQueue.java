package io.softa.starter.message.push.queue;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code push-send} topic ({@code mq.topics.push-send.topic}). Without it nothing is queued and nothing is pushed,
 * which the startup log says once.
 */
@Slf4j
@Component
public class AppPushQueue {

    private final String topic;
    private final ObjectProvider<PulsarTemplate<AppPushMessage>> pulsar;

    public AppPushQueue(@Value("${mq.topics.push-send.topic:}") String topic,
                        ObjectProvider<PulsarTemplate<AppPushMessage>> pulsar) {
        this.topic = topic;
        this.pulsar = pulsar;
        if (StringUtils.isBlank(topic)) {
            log.info("Push: mq.topics.push-send.topic is not set, inbox messages are not pushed");
        }
    }

    boolean available() {
        return StringUtils.isNotBlank(topic) && pulsar.getIfAvailable() != null;
    }

    /** Queues it without waiting for the broker; a failure, then or later, is logged and the push is lost. */
    void publish(AppPushMessage message) {
        try {
            pulsar.getObject().newMessage(message).withTopic(topic).sendAsync().whenComplete((id, error) -> {
                if (error != null) {
                    log.error("Push: notification={} tenant={} not queued: {}", message.notificationId(),
                            message.tenantId(), error.toString());
                } else {
                    log.info("Push: notification={} tenant={} queued", message.notificationId(), message.tenantId());
                }
            });
        } catch (RuntimeException e) {
            log.error("Push: notification={} tenant={} not queued: {}", message.notificationId(), message.tenantId(),
                    e.toString());
        }
    }
}
