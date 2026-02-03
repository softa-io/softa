package io.softa.starter.metadata.message;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

import io.softa.starter.metadata.message.dto.InnerBroadcastMessage;

/**
 * Reload metadata producer
 */
@Slf4j
@Component
public class InnerBroadcastProducer {

    @Value("${mq.topics.inner-broadcast.topic:}")
    private String innerBroadcastTopic;

    @Autowired
    private PulsarTemplate<InnerBroadcastMessage> pulsarTemplate;

    /**
     * Send an inner broadcast message to MQ.
     */
    public void sendInnerBroadcast(InnerBroadcastMessage message) {
        if (StringUtils.isBlank(innerBroadcastTopic)) {
            log.warn("mq.topics.inner-broadcast.topic not configured!");
            return;
        }
        pulsarTemplate.sendAsync(innerBroadcastTopic, message).whenComplete((messageId, ex) -> {
            if (ex != null) {
                log.error("Failed to send inner broadcast message to MQ!", ex);
            }
        });
    }
}
