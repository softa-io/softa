package io.softa.starter.message.sms.message;

import io.softa.framework.base.context.Context;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pulsar message payload for asynchronous SMS sending.
 * <p>
 * Carries the full {@link SendSmsDTO} and the propagated request {@link Context}
 * so that the consumer can restore tenant/user context before invoking
 * {@link io.softa.starter.message.sms.service.SmsSendService#sendNow(SendSmsDTO)}.
 */
@Data
@NoArgsConstructor
public class SmsSendMessage {

    /** The SMS send request to be executed asynchronously. */
    private SendSmsDTO sendSmsDTO;

    /** Propagated request context (tenant, user, language, etc.). */
    private Context context;

    public SmsSendMessage(SendSmsDTO sendSmsDTO, Context context) {
        this.sendSmsDTO = sendSmsDTO;
        this.context = context;
    }
}
