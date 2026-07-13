package io.softa.starter.message.dlq.message;

import io.softa.starter.message.dlq.entity.DeadLetterMessage;
import io.softa.starter.message.dlq.enums.DeadLetterSource;
import io.softa.starter.message.dlq.enums.DeadLetterStatus;
import io.softa.starter.message.dlq.service.DeadLetterMessageService;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.common.schema.SchemaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

/**
 * Catches all dead-lettered messages from the unified DLQ topic, persists them
 * to {@code dead_letter_message} for human triage, then sends a notification mail.
 *
 * <p>Failure-resilient by design:
 * <ul>
 *   <li>Envelope-agnostic parsing: reads raw JSON into a {@link JsonNode} and
 *       extracts {@code eventType / sourceId / tenantId / payload} by field-name
 *       convention; supports any sub-suite's envelope.</li>
 *   <li>All exceptions caught — re-throwing would push the dead letter into a
 *       DLQ-of-DLQ loop.</li>
 *   <li>{@code payload} always non-null: structured payload → entire root tree →
 *       raw string wrapped via {@code valueToTree}. Raw payload is never lost.</li>
 *   <li>Mail notification is best-effort: a mail failure never blocks archival.</li>
 * </ul>
 *
 * <p><strong>Activation</strong>: requires {@code softa.message.dlq.topic} to be set.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "softa.message.dlq", name = "topic")
@Slf4j
public class DeadLetterConsumer {

    private final ObjectMapper mapper;
    private final DeadLetterMessageService dlqService;
    private final MessageService messageService;

    /** Comma-separated recipient list from {@code softa.message.dlq.alert.recipients}. Empty = no mail. */
    @Value("${softa.message.dlq.alert.recipients:}")
    private String alertRecipientsRaw;

    /**
     * Pulsar listener entry point: archive one DLQ message and fire an alert mail.
     * All exceptions are swallowed to avoid a DLQ-of-DLQ redelivery loop.
     *
     * @param rawMessage raw DLQ message; properties carry broker-set {@code REAL_TOPIC} and {@code REAL_SUBSCRIPTION}
     */
    @PulsarListener(
            topics = "${softa.message.dlq.topic}",
            subscriptionName = "message-dlq-archive",
            subscriptionType = SubscriptionType.Shared,
            schemaType = SchemaType.STRING)
    public void onDeadLetter(Message<String> rawMessage) {
        try {
            String rawJson = rawMessage.getValue();
            String dlqTopic = rawMessage.getTopicName();
            String originalTopic = rawMessage.getProperty("REAL_TOPIC");
            if (originalTopic == null) {
                originalTopic = dlqTopic;
            }

            // Best-effort JSON parse; even malformed JSON is preserved via valueToTree.
            JsonNode root;
            try {
                root = mapper.readTree(rawJson);
            } catch (Exception readEx) {
                log.warn("Raw JSON parse failed; wrapping as TextNode. dlqTopic={}", dlqTopic, readEx);
                root = mapper.valueToTree(rawJson);
            }

            // Extract metadata by envelope field-name convention; missing → null.
            Long sourceTenantId = readLong(root, "tenantId");
            Long eventId = readLong(root, "eventId");
            String eventType = readString(root, "eventType");
            JsonNode payload = root.has("payload") ? root.get("payload") : root;

            DeadLetterMessage record = DeadLetterMessage.builder()
                    .source(DeadLetterSource.BROKER_POISON)
                    .sourceTenantId(sourceTenantId)
                    .originalTopic(originalTopic)
                    .dlqTopic(dlqTopic)
                    .subscriptionName(rawMessage.getProperty("REAL_SUBSCRIPTION"))
                    .eventType(eventType)
                    .eventId(eventId)
                    .payload(payload)
                    .status(DeadLetterStatus.PENDING)
                    .lastErrorMsg(null)
                    .build();
            record = dlqService.createOneAndFetch(record);
            
            log.warn("Archived dead letter: id={} , originalTopic={} , eventType={} , eventId={} , sourceTenantId={}",
                    record.getId(), originalTopic, eventType, eventId, sourceTenantId);

            sendAlertMail(record);
        } catch (Exception e) {
            log.error("[CRITICAL] Failed to archive dead letter; manual inspection required. dlqTopic={}",
                    rawMessage.getTopicName(), e);
        }
    }

    /**
     * Best-effort mail notification. Failures never bubble up.
     *
     * @param record the archived dead-letter row
     */
    private void sendAlertMail(DeadLetterMessage record) {
        List<String> recipients = parseRecipients(alertRecipientsRaw);
        if (recipients.isEmpty()) {
            log.debug("DLQ alert mail skipped: softa.message.dlq.alert.recipients is empty");
            return;
        }
        try {
            String subject = String.format("[DLQ] Dead letter archived: %s", record.getOriginalTopic());
            String body = String.format(
                    "A dead letter was archived for triage.%n%n"
                            + "Id: %s%n"
                            + "Original topic: %s%n"
                            + "DLQ topic: %s%n"
                            + "Subscription: %s%n"
                            + "Event type: %s%n"
                            + "Event id: %s%n"
                            + "Source tenant id: %s%n",
                    record.getId(),
                    record.getOriginalTopic(),
                    record.getDlqTopic(),
                    record.getSubscriptionName(),
                    record.getEventType(),
                    record.getEventId(),
                    record.getSourceTenantId());
            SendMailDTO message = new SendMailDTO();
            message.setTo(recipients);
            message.setSubject(subject);
            message.setTextBody(body);
            messageService.sendMail(message);
        } catch (Exception mailEx) {
            log.error("DLQ alert mail send failed (archive succeeded). recordId={}", record.getId(), mailEx);
        }
    }

    /**
     * Parse the comma-separated recipient configuration value, trimming whitespace and dropping blanks.
     *
     * @param raw raw configuration value (nullable)
     * @return list of recipient addresses; empty when input has no entries
     */
    private static List<String> parseRecipients(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
    
    /**
     * Read a String field from the envelope tree by name.
     *
     * @param root parsed envelope tree
     * @param name field name to read
     * @return the field value, or {@code null} when missing or JSON null
     */
    private static String readString(JsonNode root, String name) {
        JsonNode n = root.get(name);
        return (n == null || n.isNull()) ? null : n.asString();
    }
    
    /**
     * Read a Long field from the envelope tree by name.
     * Tolerates SOFTA's global ObjectMapper serialising Long as String.
     *
     * @param root parsed envelope tree
     * @param name field name to read
     * @return the field value, or {@code null} when missing, JSON null, or unparseable
     */
    private static Long readLong(JsonNode root, String name) {
        JsonNode n = root.get(name);
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return n.isString() ? Long.parseLong(n.asString()) : n.asLong();
        } catch (NumberFormatException e) {
            log.warn("readLong: field '{}' value '{}' is not a valid Long", name, n.asString(), e);
            return null;
        }
    }
}
