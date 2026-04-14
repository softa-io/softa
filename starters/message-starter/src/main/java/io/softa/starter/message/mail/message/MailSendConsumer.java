package io.softa.starter.message.mail.message;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.service.MailSendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

/**
 * Consumes asynchronous mail-send messages from Pulsar and triggers the actual send.
 * <p>
 * Only activated when {@code mq.topics.mail-send.topic} is configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.mail-send.topic")
public class MailSendConsumer {

    @Autowired
    private MailSendService mailSendService;

    @PulsarListener(topics = "${mq.topics.mail-send.topic}",
                    subscriptionName = "${mq.topics.mail-send.sub:mail-send-sub}")
    public void onSend(MailSendMessage message) {
        Context ctx = message.getContext();
        ContextHolder.runWith(ctx != null ? ctx : new Context(), () -> {
            SendMailDTO dto = message.getSendMailDTO();
            log.info("Processing async mail send: recipients={}",
                    dto.getTo() != null ? dto.getTo().size() : 0);
            mailSendService.sendNow(dto);
        });
    }
}
