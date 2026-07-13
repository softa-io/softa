package io.softa.starter.message.mq.outbox;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.softa.starter.message.mq.TopicRoute;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit contract for {@link OutboxRecordWriter}: the record-write and the outbox
 * enqueue happen together, and the delayed-retry enqueue is skipped when the CAS
 * loses. The transactional <i>atomicity</i> (joint commit / rollback) is a proxy
 * concern that only a JDBC/integration slice can prove — tracked separately.
 */
class OutboxRecordWriterTest {

    @Test
    void persistAndEnqueue_persistsThenEnqueues_andReturnsRecordId() {
        OutboxService outboxService = mock(OutboxService.class);
        OutboxRecordWriter writer = new OutboxRecordWriter(outboxService);

        Long id = writer.persistAndEnqueue(() -> 42L, "MailSendRecord", TopicRoute.MAIL_SEND);

        Assertions.assertEquals(42L, id);
        verify(outboxService).enqueue(eq("MailSendRecord"), eq(42L), eq(TopicRoute.MAIL_SEND), anyString());
    }

    @Test
    void transitionAndEnqueueAt_enqueuesDelayedRow_whenCasSucceeds() {
        OutboxService outboxService = mock(OutboxService.class);
        OutboxRecordWriter writer = new OutboxRecordWriter(outboxService);
        LocalDateTime fireAt = LocalDateTime.now().plusMinutes(5);

        boolean transitioned = writer.transitionAndEnqueueAt(
                () -> true, 7L, "SmsSendRecord", TopicRoute.SMS_SEND, fireAt);

        Assertions.assertTrue(transitioned);
        verify(outboxService).enqueueAt(eq("SmsSendRecord"), eq(7L), eq(TopicRoute.SMS_SEND), anyString(), eq(fireAt));
    }

    @Test
    void transitionAndEnqueueAt_skipsEnqueue_whenCasLoses() {
        OutboxService outboxService = mock(OutboxService.class);
        OutboxRecordWriter writer = new OutboxRecordWriter(outboxService);

        boolean transitioned = writer.transitionAndEnqueueAt(
                () -> false, 7L, "SmsSendRecord", TopicRoute.SMS_SEND, LocalDateTime.now());

        Assertions.assertFalse(transitioned);
        verifyNoInteractions(outboxService);
    }
}
