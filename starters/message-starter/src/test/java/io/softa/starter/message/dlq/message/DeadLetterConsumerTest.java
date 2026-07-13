package io.softa.starter.message.dlq.message;

import io.softa.starter.message.dlq.entity.DeadLetterMessage;
import io.softa.starter.message.dlq.service.DeadLetterMessageService;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;
import org.apache.pulsar.client.api.Message;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeadLetterConsumerTest {

    private DeadLetterConsumer consumer;
    private ObjectMapper mapper;
    private DeadLetterMessageService dlqService;
    private MessageService messageService;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        dlqService = mock(DeadLetterMessageService.class);
        messageService = mock(MessageService.class);
        consumer = new DeadLetterConsumer(mapper, dlqService, messageService);

        // createOneAndFetch returns the same record back with an id stamped (mimic ORM behaviour).
        when(dlqService.createOneAndFetch(any(DeadLetterMessage.class))).thenAnswer(inv -> {
            DeadLetterMessage rec = inv.getArgument(0);
            rec.setId(1L);
            return rec;
        });
    }

    @SuppressWarnings("unchecked")
    private Message<String> mockPulsarMessage(String rawJson, String realTopic, String realSubscription, String topicName) {
        Message<String> msg = mock(Message.class);
        when(msg.getValue()).thenReturn(rawJson);
        when(msg.getTopicName()).thenReturn(topicName);
        when(msg.getProperty("REAL_TOPIC")).thenReturn(realTopic);
        when(msg.getProperty("REAL_SUBSCRIPTION")).thenReturn(realSubscription);
        return msg;
    }

    @Test
    void onDeadLetter_happyPath_archivesAndSendsMail() {
        ReflectionTestUtils.setField(consumer, "alertRecipientsRaw", "ops@example.com");
        Message<String> msg = mockPulsarMessage(
                "{\"tenantId\":1,\"eventType\":\"OVERTIME_APPROVED\",\"eventId\":42,\"payload\":{\"foo\":\"bar\"}}",
                "hcm-overtime-event-topic",
                "leave-approved-sub",
                "hcm-workforce-dlq-topic");

        consumer.onDeadLetter(msg);

        ArgumentCaptor<DeadLetterMessage> captor = ArgumentCaptor.forClass(DeadLetterMessage.class);
        verify(dlqService, times(1)).createOneAndFetch(captor.capture());
        DeadLetterMessage saved = captor.getValue();
        Assertions.assertEquals(1L, saved.getSourceTenantId());
        Assertions.assertEquals("OVERTIME_APPROVED", saved.getEventType());
        Assertions.assertEquals(42L, saved.getEventId());
        Assertions.assertEquals("hcm-overtime-event-topic", saved.getOriginalTopic());
        Assertions.assertEquals("hcm-workforce-dlq-topic", saved.getDlqTopic());
        Assertions.assertEquals("leave-approved-sub", saved.getSubscriptionName());
        JsonNode payload = saved.getPayload();
        Assertions.assertEquals("bar", payload.get("foo").asString());

        ArgumentCaptor<SendMailDTO> mailCaptor = ArgumentCaptor.forClass(SendMailDTO.class);
        verify(messageService, times(1)).sendMail(mailCaptor.capture());
        Assertions.assertEquals(List.of("ops@example.com"), mailCaptor.getValue().getTo());
        Assertions.assertTrue(mailCaptor.getValue().getSubject().contains("hcm-overtime-event-topic"));
    }

    @Test
    void onDeadLetter_malformedJson_archivesWithRawPayload() {
        ReflectionTestUtils.setField(consumer, "alertRecipientsRaw", "");
        String brokenJson = "{this is broken";
        Message<String> msg = mockPulsarMessage(
                brokenJson,
                null,
                null,
                "hcm-workforce-dlq-topic");

        Assertions.assertDoesNotThrow(() -> consumer.onDeadLetter(msg));

        ArgumentCaptor<DeadLetterMessage> captor = ArgumentCaptor.forClass(DeadLetterMessage.class);
        verify(dlqService, times(1)).createOneAndFetch(captor.capture());
        DeadLetterMessage saved = captor.getValue();
        Assertions.assertNull(saved.getSourceTenantId());
        Assertions.assertNull(saved.getEventType());
        Assertions.assertNull(saved.getEventId());
        // REAL_TOPIC is null → falls back to dlqTopic.
        Assertions.assertEquals("hcm-workforce-dlq-topic", saved.getOriginalTopic());
        // valueToTree wraps the raw String into a string-type JsonNode preserving its content.
        Assertions.assertEquals(brokenJson, saved.getPayload().asString());

        verify(messageService, never()).sendMail(any(SendMailDTO.class));
    }

    @Test
    void onDeadLetter_noPayloadField_usesEntireRootAsPayload() {
        // envelope without a "payload" field — the entire root tree is stored as payload
        // so triage operators still see the full original message.
        Message<String> msg = mockPulsarMessage(
                "{\"tenantId\":7,\"eventType\":\"NO_PAYLOAD_EVENT\",\"eventId\":42}",
                "hcm-overtime-event-topic",
                null,
                "hcm-workforce-dlq-topic");

        consumer.onDeadLetter(msg);

        ArgumentCaptor<DeadLetterMessage> captor = ArgumentCaptor.forClass(DeadLetterMessage.class);
        verify(dlqService, times(1)).createOneAndFetch(captor.capture());
        JsonNode payload = captor.getValue().getPayload();
        Assertions.assertFalse(payload.has("payload"), "payload should not be nested under another 'payload' key");
        Assertions.assertEquals(7L, payload.get("tenantId").asLong());
        Assertions.assertEquals("NO_PAYLOAD_EVENT", payload.get("eventType").asString());
        Assertions.assertEquals(42L, payload.get("eventId").asLong());
    }

    @Test
    void onDeadLetter_longSerializedAsString_parsesCorrectly() {
        Message<String> msg = mockPulsarMessage(
                "{\"tenantId\":\"1234567890\",\"eventId\":\"9999\",\"payload\":{}}",
                "hcm-overtime-event-topic",
                null,
                "hcm-workforce-dlq-topic");

        consumer.onDeadLetter(msg);

        ArgumentCaptor<DeadLetterMessage> captor = ArgumentCaptor.forClass(DeadLetterMessage.class);
        verify(dlqService, times(1)).createOneAndFetch(captor.capture());
        DeadLetterMessage saved = captor.getValue();
        Assertions.assertEquals(1234567890L, saved.getSourceTenantId());
        Assertions.assertEquals(9999L, saved.getEventId());
    }

    @Test
    void onDeadLetter_missingTenantAndEventType_stillArchives() {
        Message<String> msg = mockPulsarMessage(
                "{\"eventId\":99,\"payload\":{}}",
                "hcm-overtime-event-topic",
                "leave-approved-sub",
                "hcm-workforce-dlq-topic");

        consumer.onDeadLetter(msg);

        ArgumentCaptor<DeadLetterMessage> captor = ArgumentCaptor.forClass(DeadLetterMessage.class);
        verify(dlqService, times(1)).createOneAndFetch(captor.capture());
        DeadLetterMessage saved = captor.getValue();
        Assertions.assertNull(saved.getSourceTenantId());
        Assertions.assertNull(saved.getEventType());
        Assertions.assertEquals(99L, saved.getEventId());
    }

    @Test
    void onDeadLetter_archiveFails_logsButNeverThrows() {
        // dlqService failure must not propagate — re-throwing would loop the dead letter back into DLQ.
        ReflectionTestUtils.setField(consumer, "alertRecipientsRaw", "ops@example.com");
        doThrow(new RuntimeException("DB down"))
                .when(dlqService).createOneAndFetch(any(DeadLetterMessage.class));
        Message<String> msg = mockPulsarMessage(
                "{\"tenantId\":1,\"eventType\":\"X\",\"eventId\":42,\"payload\":{}}",
                "hcm-overtime-event-topic",
                "leave-approved-sub",
                "hcm-workforce-dlq-topic");

        Assertions.assertDoesNotThrow(() -> consumer.onDeadLetter(msg));

        // Mail is gated on a successful archive — when archive fails, no mail goes out.
        verify(messageService, never()).sendMail(any(SendMailDTO.class));
    }

    @Test
    void onDeadLetter_mailFails_archivalStillSucceeds() {
        // Mail send failure is independently swallowed — archival is the source of truth.
        ReflectionTestUtils.setField(consumer, "alertRecipientsRaw", "ops@example.com");
        doThrow(new RuntimeException("SMTP down"))
                .when(messageService).sendMail(any(SendMailDTO.class));
        Message<String> msg = mockPulsarMessage(
                "{\"tenantId\":1,\"eventType\":\"X\",\"eventId\":42,\"payload\":{}}",
                "hcm-overtime-event-topic",
                "leave-approved-sub",
                "hcm-workforce-dlq-topic");

        Assertions.assertDoesNotThrow(() -> consumer.onDeadLetter(msg));

        verify(dlqService, times(1)).createOneAndFetch(any(DeadLetterMessage.class));
    }

    @Test
    void onDeadLetter_recipientsWithCommasAndSpaces_parsesCorrectly() {
        // Recipients config tolerates trailing/leading whitespace and empty entries from sloppy yaml.
        ReflectionTestUtils.setField(consumer, "alertRecipientsRaw", "  a@x.com , , b@y.com,c@z.com  ");
        Message<String> msg = mockPulsarMessage(
                "{\"tenantId\":1,\"eventType\":\"X\",\"eventId\":42,\"payload\":{}}",
                "hcm-overtime-event-topic",
                "leave-approved-sub",
                "hcm-workforce-dlq-topic");

        consumer.onDeadLetter(msg);

        ArgumentCaptor<SendMailDTO> mailCaptor = ArgumentCaptor.forClass(SendMailDTO.class);
        verify(messageService, times(1)).sendMail(mailCaptor.capture());
        Assertions.assertEquals(List.of("a@x.com", "b@y.com", "c@z.com"), mailCaptor.getValue().getTo());
    }

}
