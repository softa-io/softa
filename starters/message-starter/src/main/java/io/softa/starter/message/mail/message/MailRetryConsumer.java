package io.softa.starter.message.mail.message;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.message.mail.service.MailSendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

/**
 * Consumes delayed mail-retry messages from Pulsar and triggers re-send.
 * <p>
 * Only activated when {@code mq.topics.mail-retry.topic} is configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.mail-retry.topic")
public class MailRetryConsumer {

    @Autowired
    private MailSendService mailSendService;

    @PulsarListener(topics = "${mq.topics.mail-retry.topic}",
                    subscriptionName = "${mq.topics.mail-retry.sub}")
    public void onRetry(MailRetryMessage message) {
        Context ctx = message.getContext();
        ContextHolder.runWith(ctx != null ? ctx : new Context(), () -> {
            log.info("Processing mail retry: sendRecordId={}", message.getSendRecordId());
            mailSendService.retrySend(message.getSendRecordId());
        });
    }
}
