package io.softa.starter.message.dlq.entity;

import java.io.Serial;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.message.dlq.enums.DeadLetterSource;
import io.softa.starter.message.dlq.enums.DeadLetterStatus;

/**
 * Unified dead-letter archive for broker-poison and send-exhausted failures.
 * Read-only from admin UIs; rows are written by the delivery pipeline.
 */
@Data
@Schema(name = "DeadLetterMessage")
@Model(
        tableName = "dead_letter_message",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        copyable = false
)
@EqualsAndHashCode(callSuper = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DeadLetterMessage extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Source Tenant Id",
            description = "Tenant that owned the original send/outbox row")
    private Long sourceTenantId;

    @Field(description = "Source: BrokerPoison (Pulsar DLQ) or SendExhausted (mail/sms retry exhausted)")
    private DeadLetterSource source;

    @Field(label = "Original Topic", length = 255)
    private String originalTopic;

    @Field(label = "DLQ Topic", length = 255)
    private String dlqTopic;

    @Field(label = "Subscription Name", length = 255)
    private String subscriptionName;

    @Field(label = "Event Type", length = 100)
    private String eventType;

    @Field(label = "Event Id")
    private Long eventId;

    @Field(fieldType = FieldType.JSON, description = "Archived message payload")
    private JsonNode payload;

    @Field(description = "Processing status of this dead-letter row")
    private DeadLetterStatus status;

    @Field(label = "Last Error Msg", description = "Last error captured when archiving / resolving")
    private String lastErrorMsg;

    @Field(label = "Resolved Remark", description = "Operator note when marking resolved")
    private String resolvedRemark;
}
