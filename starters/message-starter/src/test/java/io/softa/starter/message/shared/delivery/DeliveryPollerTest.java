package io.softa.starter.message.shared.delivery;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.impl.MailDeliveryProcessor;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.impl.SmsDeliveryProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The delivery loop claims due records — never more than it has free workers — and delivers each one
 * it won, on a worker, in the record's own tenant and trace.
 */
class DeliveryPollerTest {

    private MailSendRecordService mailRecords;
    private MailDeliveryProcessor mailProcessor;
    private MessageProperties properties;
    private DeliveryPoller poller;

    @BeforeEach
    void setUp() {
        mailRecords = mock(MailSendRecordService.class);
        mailProcessor = mock(MailDeliveryProcessor.class);
        SmsSendRecordService smsRecords = mock(SmsSendRecordService.class);
        when(smsRecords.searchList(any(FlexQuery.class))).thenReturn(List.of());
        properties = new MessageProperties();
        poller = new DeliveryPoller(mailRecords, smsRecords, mailProcessor, mock(SmsDeliveryProcessor.class),
                properties);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        poller.destroy();
    }

    private static MailSendRecord due(long id) {
        MailSendRecord r = new MailSendRecord();
        r.setId(id);
        r.setTenantId(42L);
        r.setTraceId("trace-" + id);
        r.setStatus(MailSendStatus.PENDING);
        r.setVersion(3L);
        return r;
    }

    @Test
    void aClaimedRecordIsDeliveredInItsTenantAndTrace() {
        MailSendRecord record = due(5L);
        when(mailRecords.searchList(any(FlexQuery.class))).thenReturn(List.of(record));
        AtomicReference<Context> claimedIn = new AtomicReference<>();
        when(mailRecords.markSending(5L, 3L)).thenAnswer(invocation -> {
            claimedIn.set(ContextHolder.getContext());
            return true;
        });
        AtomicReference<Context> deliveredIn = new AtomicReference<>();
        doAnswer(invocation -> {
            deliveredIn.set(ContextHolder.getContext());
            return null;
        }).when(mailProcessor).deliver(record, 4L);

        poller.poll();

        // The claim bumped the version: the processor owns the record at version 4.
        verify(mailProcessor, timeout(2000)).deliver(record, 4L);
        assertThat(claimedIn.get().getTenantId()).isEqualTo(42L);
        assertThat(claimedIn.get().isSkipPermissionCheck()).isTrue();
        assertThat(deliveredIn.get().getTenantId()).isEqualTo(42L);
        assertThat(deliveredIn.get().getTraceId()).isEqualTo("trace-5");
        assertThat(deliveredIn.get().isCrossTenant()).isFalse();
    }

    @Test
    void aClaimLostToAnotherInstanceIsNotDelivered() {
        when(mailRecords.searchList(any(FlexQuery.class))).thenReturn(List.of(due(5L)));
        when(mailRecords.markSending(5L, 3L)).thenReturn(false);

        poller.poll();

        verify(mailProcessor, after(200).never()).deliver(any(), anyLong());
    }

    @Test
    void itNeverClaimsMoreThanItHasFreeWorkers() throws InterruptedException {
        properties.getMail().setSendConcurrency(1);
        poller.destroy();
        SmsSendRecordService smsRecords = mock(SmsSendRecordService.class);
        when(smsRecords.searchList(any(FlexQuery.class))).thenReturn(List.of());
        poller = new DeliveryPoller(mailRecords, smsRecords, mailProcessor, mock(SmsDeliveryProcessor.class),
                properties);
        MailSendRecord first = due(5L);
        MailSendRecord second = due(6L);
        when(mailRecords.searchList(any(FlexQuery.class))).thenReturn(List.of(first, second));
        when(mailRecords.markSending(anyLong(), eq(3L))).thenReturn(true);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(mailProcessor).deliver(first, 4L);

        poller.poll();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        // The only worker is busy: the second record is left due, unclaimed.
        verify(mailRecords, never()).markSending(6L, 3L);
        // ... and the next poll does not even look while no worker is free.
        poller.poll();
        ArgumentCaptor<FlexQuery> queries = ArgumentCaptor.forClass(FlexQuery.class);
        verify(mailRecords).searchList(queries.capture());
        assertThat(queries.getValue().getLimitSize()).isEqualTo(1);
        release.countDown();
    }
}
