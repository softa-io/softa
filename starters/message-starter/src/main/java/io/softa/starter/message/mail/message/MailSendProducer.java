package io.softa.starter.message.mail.message;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a {@link MailSendMessage} to the Pulsar mail-send topic for
 * asynchronous processing.
 * <p>
 * If the topic is not configured or Pulsar is not available, the message
 * is silently dropped and a warning is logged — the caller should fall
 * back to {@code @Async} thread-pool-based sending.
 */
@Slf4j
@Component
public class MailSendProducer {

    @Value("${mq.topics.mail-send.topic:}")
    private String sendTopic;

    @Autowired(required = false)
    private PulsarTemplate<MailSendMessage> pulsarTemplate;

    public boolean isAvailable() {
        return StringUtils.isNotBlank(sendTopic) && pulsarTemplate != null;
    }

    /**
     * Publish a mail send message for asynchronous processing.
     */
    public void send(MailSendMessage message) {
        if (!isAvailable()) {
            log.warn("MailSendProducer: topic is not configured or Pulsar is unavailable. Message will not be queued.");
            return;
        }
        pulsarTemplate.newMessage(message)
                .withTopic(sendTopic)
                .sendAsync()
                .whenComplete((msgId, ex) -> {
                    if (ex == null) {
                        log.debug("MailSendProducer: message queued to topic '{}': {}", sendTopic, msgId);
                    } else {
                        log.error("MailSendProducer: failed to queue message to topic '{}': {}",
                                sendTopic, ex.getMessage(), ex);
                    }
                });
    }
}
