package io.softa.starter.message.sms.message;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.service.SmsSendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

/**
 * Consumes asynchronous SMS-send messages from Pulsar and triggers the actual send.
 * <p>
 * Only activated when {@code mq.topics.sms-send.topic} is configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.sms-send.topic")
public class SmsSendConsumer {

    @Autowired
    private SmsSendService smsSendService;

    @PulsarListener(topics = "${mq.topics.sms-send.topic}",
                    subscriptionName = "${mq.topics.sms-send.sub:sms-send-sub}")
    public void onSend(SmsSendMessage message) {
        Context ctx = message.getContext();
        ContextHolder.runWith(ctx != null ? ctx : new Context(), () -> {
            SendSmsDTO dto = message.getSendSmsDTO();
            log.info("Processing async SMS send: batchSize={}",
                    !CollectionUtils.isEmpty(dto.getItems()) ? dto.getItems().size() : 1);
            smsSendService.sendNow(dto);
        });
    }
}
