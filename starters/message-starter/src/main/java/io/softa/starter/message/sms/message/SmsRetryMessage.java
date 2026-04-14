package io.softa.starter.message.sms.message;

import io.softa.framework.base.context.Context;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pulsar message payload for delayed SMS-send retry.
 */
@Data
@NoArgsConstructor
public class SmsRetryMessage {

    /** Primary key of the {@code SmsSendRecord} to retry. */
    private Long sendRecordId;

    /** Primary key of the {@code SmsProviderConfig} used for this send. */
    private Long providerConfigId;

    /** Propagated request context (tenant, user, language, etc.). */
    private Context context;

    public SmsRetryMessage(Long sendRecordId, Long providerConfigId, Context context) {
        this.sendRecordId = sendRecordId;
        this.providerConfigId = providerConfigId;
        this.context = context;
    }
}
