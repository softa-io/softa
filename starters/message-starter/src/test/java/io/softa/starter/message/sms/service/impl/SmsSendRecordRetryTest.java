package io.softa.starter.message.sms.service.impl;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsSendStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The bulk retry requeues what the single retry accepts and skips the rest: SENT,
 * in-flight SENDING, and a record that changed while being requeued.
 */
class SmsSendRecordRetryTest {

    @Test
    void bulkRetry_requeuesWhatRetryAccepts_andSkipsTheRest() {
        SmsSendRecordServiceImpl service = Mockito.spy(new SmsSendRecordServiceImpl());
        List<Long> ids = List.of(1L, 2L, 3L, 4L);
        doReturn(List.of(
                record(1L, SmsSendStatus.DEAD_LETTER),
                record(2L, SmsSendStatus.SENT),
                record(3L, SmsSendStatus.SENDING),
                record(4L, SmsSendStatus.FAILED))).when(service).getByIds(ids);
        doReturn(true).when(service).markRetry(eq(1L), eq(3L), eq("MANUAL_RETRY"), any(), any());
        doReturn(false).when(service).markRetry(eq(4L), eq(3L), eq("MANUAL_RETRY"), any(), any());

        assertEquals(1, service.retryByIds(ids));

        verify(service, never()).markRetry(eq(2L), anyLong(), any(), any(), any());
        verify(service, never()).markRetry(eq(3L), anyLong(), any(), any(), any());
    }

    private static SmsSendRecord record(Long id, SmsSendStatus status) {
        SmsSendRecord record = new SmsSendRecord();
        record.setId(id);
        record.setStatus(status);
        record.setVersion(3L);
        return record;
    }
}
