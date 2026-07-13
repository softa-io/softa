package io.softa.starter.message.mq.outbox;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.mq.TopicRoute;

/**
 * Transactional outbox entry.
 * <p>
 * Written in the same DB transaction as the owning business record
 * (MailSendRecord / SmsSendRecord). A scheduled publisher later reads
 * {@link OutboxStatus#NEW} rows, claims them as {@link OutboxStatus#PUBLISHING}
 * through the framework optimistic lock, publishes them to the broker, and
 * flips them to {@link OutboxStatus#PUBLISHED}.
 * <p>
 * The payload is the raw message body (typically a small JSON carrying the
 * record id plus tenant / trace context); no provider-specific payloads or
 * PII are duplicated here — the consumer re-reads from the business record.
 */
@Data
@Model(
    tableName = "message_outbox",
    idStrategy = IdStrategy.DISTRIBUTED_LONG,
    versionLock = true,
    copyable = false
)
@Index(indexName = "idx_status_next", fields = {"status", "nextAttemptAt"})
@Index(indexName = "idx_aggregate", fields = {"aggregateType", "aggregateId"})
@EqualsAndHashCode(callSuper = true)
public class OutboxEntry extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(required = true, length = 50,
        description = "Aggregate type, e.g. MailSendRecord / SmsSendRecord (for diagnostics)")
    private String aggregateType;

    @Field(label = "Aggregate ID", required = true,
        description = "Aggregate primary key the message refers to")
    private Long aggregateId;

    @Field(description = "Logical delivery route name (MAIL_SEND / SMS_SEND)")
    private TopicRoute route;

    @Field(required = true,
        description = "Message body serialized as JSON")
    private String payload;

    @Field(description = "Lifecycle status")
    private OutboxStatus status;

    @Field(required = true,
        description = "Number of publish attempts so far")
    private Integer attempts;

    @Field(length = 500,
        description = "Last publish error message (for dead rows)")
    private String lastError;

    @Field(description = "Earliest time the publisher should pick this row up next")
    private LocalDateTime nextAttemptAt;

    @Field(description = "Timestamp when the row was published to the broker")
    private LocalDateTime publishedAt;

    @Field(required = true, description = "Optimistic-lock version")
    private Long version;
}
