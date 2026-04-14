package io.softa.starter.message.mail.message;

import io.softa.framework.base.context.Context;
import io.softa.starter.message.mail.dto.SendMailDTO;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pulsar message payload for asynchronous mail sending.
 * <p>
 * Carries the full {@link SendMailDTO} and the propagated request {@link Context}
 * so that the consumer can restore tenant/user context before invoking
 * {@link io.softa.starter.message.mail.service.MailSendService#sendNow(SendMailDTO)}.
 */
@Data
@NoArgsConstructor
public class MailSendMessage {

    private SendMailDTO sendMailDTO;

    private Context context;

    public MailSendMessage(SendMailDTO sendMailDTO, Context context) {
        this.sendMailDTO = sendMailDTO;
        this.context = context;
    }
}
