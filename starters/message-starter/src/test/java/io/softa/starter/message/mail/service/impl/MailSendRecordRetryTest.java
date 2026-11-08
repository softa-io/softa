package io.softa.starter.message.mail.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailSendStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * in-flight SENDING are rejected before anything is written. The bulk retry applies
 * the same gate and skips what it would reject.
 */
class MailSendRecordRetryTest {

    private MailSendRecordServiceImpl service;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new MailSendRecordServiceImpl());
    }

    private void record(MailSendStatus status) {
        doReturn(Optional.of(record(7L, status))).when(service).getById(7L);
    }

    private static MailSendRecord record(Long id, MailSendStatus status) {
        MailSendRecord record = new MailSendRecord();
        record.setId(id);
        record.setStatus(status);
        record.setVersion(3L);
        return record;
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
    void bulkRetry_requeuesWhatRetryAccepts_andSkipsTheRest() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L);
        doReturn(List.of(
                record(1L, MailSendStatus.FAILED),
                record(2L, MailSendStatus.SENT),
                record(3L, MailSendStatus.DEAD_LETTER),
                record(4L, MailSendStatus.SENDING),
                record(5L, MailSendStatus.RETRY))).when(service).getByIds(ids);
        doReturn(true).when(service).markRetry(anyLong(), eq(3L), eq("MANUAL_RETRY"), any(), any());
        // 5 was claimed by a worker between the read and the requeue.
        doReturn(false).when(service).markRetry(eq(5L), eq(3L), eq("MANUAL_RETRY"), any(), any());

        assertEquals(2, service.retryByIds(ids));

        verify(service).markRetry(eq(1L), eq(3L), eq("MANUAL_RETRY"), any(), any());
        verify(service).markRetry(eq(3L), eq(3L), eq("MANUAL_RETRY"), any(), any());
        verify(service, never()).markRetry(eq(2L), anyLong(), any(), any(), any());
        verify(service, never()).markRetry(eq(4L), anyLong(), any(), any(), any());
    }

    @Test
    void concurrentVersionMiss_surfacesAsBusinessError() {
        record(MailSendStatus.RETRY);
        doReturn(false).when(service).markRetry(eq(7L), eq(3L), eq("MANUAL_RETRY"), any(), any());

        assertThrows(BusinessException.class, () -> service.retry(7L));
    }
}
