package io.softa.starter.message.sms.message;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.message.sms.service.SmsSendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

/**
 * Consumes delayed SMS-retry messages from Pulsar and triggers re-send.
 * <p>
 * Only activated when {@code mq.topics.sms-retry.topic} is configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.sms-retry.topic")
public class SmsRetryConsumer {

    @Autowired
    private SmsSendService smsSendService;

    @PulsarListener(topics = "${mq.topics.sms-retry.topic}",
                    subscriptionName = "${mq.topics.sms-retry.sub}")
    public void onRetry(SmsRetryMessage message) {
        Context ctx = message.getContext();
        ContextHolder.runWith(ctx != null ? ctx : new Context(), () -> {
            log.info("Processing SMS retry: sendRecordId={}", message.getSendRecordId());
            smsSendService.retrySend(message.getSendRecordId());
        });
    }
}
