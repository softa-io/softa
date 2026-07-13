package io.softa.starter.message.mail.message;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.service.impl.MailDeliveryProcessor;
import io.softa.starter.message.mq.outbox.OutboxContextSupport;
import io.softa.starter.message.mq.outbox.OutboxMessage;

/**
 * Consumes mail delivery messages from Pulsar and drives the delivery processor.
 * Initial attempts and delayed retries share this topic; retry timing is owned
 * by the transactional outbox.
 * <p>
 * Messages carry only {@code recordId} (plus tenant/trace for context).
 * Duplicate broker deliveries are safely rejected by the CAS claim in
 * {@link MailDeliveryProcessor#process(Long)}.
 * <p>
 * Only activated when {@code mq.topics.mail-send.topic} is configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.mail-send.topic")
public class MailSendConsumer {

    @Autowired
    private MailDeliveryProcessor deliveryProcessor;

    @PulsarListener(topics = "${mq.topics.mail-send.topic}",
                    subscriptionName = "${mq.topics.mail-send.sub:mail-send-sub}")
    public void onSend(OutboxMessage message) {
        OutboxContextSupport.runWithContext(message, () -> {
            log.debug("Processing mail delivery: recordId={} traceId={}",
                    message.getRecordId(), message.getTraceId());
            deliveryProcessor.process(message.getRecordId());
        });
    }
}
