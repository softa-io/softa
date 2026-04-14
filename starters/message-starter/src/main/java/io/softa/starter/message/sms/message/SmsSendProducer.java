package io.softa.starter.message.sms.message;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes an {@link SmsSendMessage} to the Pulsar SMS-send topic for
 * asynchronous processing.
 * <p>
 * If the topic is not configured or Pulsar is not available, the message
 * is silently dropped and a warning is logged — the caller should fall
 * back to synchronous or thread-pool-based sending.
 */
@Slf4j
@Component
public class SmsSendProducer {

    @Value("${mq.topics.sms-send.topic:}")
    private String sendTopic;

    @Autowired(required = false)
    private PulsarTemplate<SmsSendMessage> pulsarTemplate;

    public boolean isAvailable() {
        return StringUtils.isNotBlank(sendTopic) && pulsarTemplate != null;
    }

    /**
     * Publish an SMS send message for asynchronous processing.
     */
    public void send(SmsSendMessage message) {
        if (!isAvailable()) {
            log.warn("SmsSendProducer: topic is not configured or Pulsar is unavailable. Message will not be queued.");
            return;
        }
        pulsarTemplate.newMessage(message)
                .withTopic(sendTopic)
                .sendAsync()
                .whenComplete((msgId, ex) -> {
                    if (ex == null) {
                        log.debug("SmsSendProducer: message queued to topic '{}': {}", sendTopic, msgId);
                    } else {
                        log.error("SmsSendProducer: failed to queue message to topic '{}': {}",
                                sendTopic, ex.getMessage(), ex);
                    }
                });
    }
}
