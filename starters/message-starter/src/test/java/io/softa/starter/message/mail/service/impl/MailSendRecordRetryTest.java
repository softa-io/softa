package io.softa.starter.message.mail.service.impl;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailSendStatus;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Status-gate contract for the manual retry: PENDING / RETRY / FAILED / DEAD_LETTER
 * become RETRY, due now, so the delivery loop takes them on its next poll; SENT and
 * in-flight SENDING are rejected before anything is written.
 */
class MailSendRecordRetryTest {

    private MailSendRecordServiceImpl service;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new MailSendRecordServiceImpl());
    }

    private void record(MailSendStatus status) {
        MailSendRecord record = new MailSendRecord();
        record.setId(7L);
        record.setStatus(status);
        record.setVersion(3L);
        doReturn(Optional.of(record)).when(service).getById(7L);
    }

    @Test
    void pendingRecord_isRequeuedDueNow() {
        record(MailSendStatus.PENDING);
        LocalDateTime before = LocalDateTime.now();
        doReturn(true).when(service).markRetry(eq(7L), eq(3L), eq("MANUAL_RETRY"), any(), any());

        assertTrue(service.retry(7L));

        verify(service).markRetry(eq(7L), eq(3L), eq("MANUAL_RETRY"), any(),
                argThat(at -> !at.isBefore(before) && !at.isAfter(LocalDateTime.now())));
    }

    @Test
    void failedRecord_isRetryable() {
        // A record that failed before it was prepared (no server, no template) is prepared afresh
        // by the next attempt: fixing the configuration and retrying is enough.
        record(MailSendStatus.FAILED);
        doReturn(true).when(service).markRetry(eq(7L), eq(3L), eq("MANUAL_RETRY"), any(), any());

        assertTrue(service.retry(7L));
    }

    @Test
    void deadLetterRecord_isRetryable() {
        record(MailSendStatus.DEAD_LETTER);
        doReturn(true).when(service).markRetry(eq(7L), eq(3L), eq("MANUAL_RETRY"), any(), any());

        assertTrue(service.retry(7L));
    }

    @Test
    void sentRecord_isRejectedWithoutWriting() {
        record(MailSendStatus.SENT);

        assertThrows(BusinessException.class, () -> service.retry(7L));
        verify(service, never()).markRetry(any(), anyLong(), any(), any(), any());
    }

    @Test
    void inFlightSendingRecord_isRejected() {
        record(MailSendStatus.SENDING);

        assertThrows(BusinessException.class, () -> service.retry(7L));
        verify(service, never()).markRetry(any(), anyLong(), any(), any(), any());
    }

    @Test
    void concurrentVersionMiss_surfacesAsBusinessError() {
        record(MailSendStatus.RETRY);
        doReturn(false).when(service).markRetry(eq(7L), eq(3L), eq("MANUAL_RETRY"), any(), any());

        assertThrows(BusinessException.class, () -> service.retry(7L));
    }
}
