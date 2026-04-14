package io.softa.starter.message.mail.message;

import io.softa.framework.base.context.Context;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pulsar message payload for delayed mail-send retry.
 */
@Data
@NoArgsConstructor
public class MailRetryMessage {

    /** Primary key of the {@code MailSendRecord} to retry. */
    private Long sendRecordId;

    /** Primary key of the {@code MailSendServerConfig} used for this send. */
    private Long serverConfigId;

    /** Propagated request context (tenant, user, language, etc.). */
    private Context context;

    public MailRetryMessage(Long sendRecordId, Long serverConfigId, Context context) {
        this.sendRecordId = sendRecordId;
        this.serverConfigId = serverConfigId;
        this.context = context;
    }
}
