package io.softa.starter.message.mail.message;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a delayed {@link MailRetryMessage} to the Pulsar mail-retry topic.
 * <p>
 * If the topic is not configured, the message is silently dropped and a
 * warning is logged — the caller should handle the fallback (e.g. mark
 * the record as FAILED directly).
 */
@Slf4j
@Component
public class MailRetryProducer {

    @Value("${mq.topics.mail-retry.topic:}")
    private String retryTopic;

    @Autowired(required = false)
    private PulsarTemplate<MailRetryMessage> pulsarTemplate;

    public boolean isAvailable() {
        return StringUtils.isNotBlank(retryTopic) && pulsarTemplate != null;
    }

    /**
     * Send a retry message with delayed delivery.
     *
     * @param message      the retry payload
     * @param delaySeconds delay before the message becomes visible to consumers
     */
    public void sendDelayed(MailRetryMessage message, int delaySeconds) {
        if (!isAvailable()) {
            log.warn("MailRetryProducer: topic is not configured or Pulsar is unavailable. Delayed message will not be queued.");
            return;
        }
        pulsarTemplate.newMessage(message)
                .withTopic(retryTopic)
                .withMessageCustomizer(builder -> builder.deliverAfter(delaySeconds, TimeUnit.SECONDS))
                .sendAsync()
                .whenComplete((msgId, ex) -> {
                    if (ex == null) {
                        log.debug("MailRetryProducer: delayed message queued (delay={}s) to topic '{}': {}",
                                delaySeconds, retryTopic, msgId);
                    } else {
                        log.error("MailRetryProducer: failed to queue delayed message to topic '{}': {}",
                                retryTopic, ex.getMessage(), ex);
                    }
                });
    }
}
