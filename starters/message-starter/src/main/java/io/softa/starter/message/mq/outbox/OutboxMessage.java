package io.softa.starter.message.mq.outbox;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Universal payload carried on every message-starter topic.
 * <p>
 * Consumers re-read the full business record by {@link #recordId} from the DB;
 * we never duplicate email body or SMS content inside the broker. Keeping the
 * payload this small means the broker is never a source of sensitive data and
 * the outbox table stays cheap.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OutboxMessage {

    /** Aggregate id — points back to MailSendRecord / SmsSendRecord. */
    private Long recordId;

    /** Tenant id propagated so the consumer can restore {@code ContextHolder}. */
    private Long tenantId;

    /** Optional trace id for end-to-end correlation. */
    private String traceId;
}
