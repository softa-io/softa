package io.softa.starter.message.shared.delivery;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The delivery loop: the poll hands due records to idle workers, never more than it has, and a worker
 * that finishes a send goes straight on to the next due record. Each record it won is delivered on a
 * worker, in the record's own tenant and trace.
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
        properties = new MessageProperties();
        poller = newPoller();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        poller.destroy();
    }

    private DeliveryPoller newPoller() {
        SmsSendRecordService smsRecords = mock(SmsSendRecordService.class);
        when(smsRecords.searchList(any(FlexQuery.class))).thenReturn(List.of());
        return new DeliveryPoller(mailRecords, smsRecords, mailProcessor, mock(SmsDeliveryProcessor.class),
                properties);
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
        when(mailRecords.searchList(any(FlexQuery.class))).thenReturn(List.of(record), List.of());
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
        when(mailRecords.searchList(any(FlexQuery.class))).thenReturn(List.of(due(5L)), List.of());
        when(mailRecords.markSending(5L, 3L)).thenReturn(false);

        poller.poll();

        verify(mailProcessor, after(200).never()).deliver(any(), anyLong());
    }

    @Test
    void aLostClaimMovesOnToTheNextDueRecord() {
        // Another instance won record 5; the worker takes record 6 in the same poll instead of idling.
        MailSendRecord taken = due(5L);
        MailSendRecord free = due(6L);
        when(mailRecords.searchList(any(FlexQuery.class))).thenReturn(List.of(taken, free), List.of());
        when(mailRecords.markSending(5L, 3L)).thenReturn(false);
        when(mailRecords.markSending(6L, 3L)).thenReturn(true);

        poller.poll();

        verify(mailProcessor, timeout(2000)).deliver(free, 4L);
        verify(mailProcessor, never()).deliver(taken, 4L);
    }

    @Test
    void aWorkerGoesStraightOnToTheNextDueRecord() {
        // One worker, one poll: the second record goes out as soon as the first is sent, not a poll later.
        MailSendRecord first = due(5L);
        MailSendRecord second = due(6L);
        when(mailRecords.searchList(any(FlexQuery.class)))
                .thenReturn(List.of(first), List.of(second), List.of());
        when(mailRecords.markSending(anyLong(), eq(3L))).thenReturn(true);

        poller.poll();

        verify(mailProcessor, timeout(2000)).deliver(second, 4L);
        InOrder order = inOrder(mailProcessor);
        order.verify(mailProcessor).deliver(first, 4L);
        order.verify(mailProcessor).deliver(second, 4L);
        // The poll's scan, then one per finished send; the last found nothing, so the worker went idle.
        verify(mailRecords, timeout(2000).times(3)).searchList(any(FlexQuery.class));
    }

    @Test
    void everyScanReadsAcrossTenants() {
        // The worker's own scan runs on a pool thread with no request context: it must not be tenant-bound.
        MailSendRecord record = due(5L);
        List<Context> scannedIn = new CopyOnWriteArrayList<>();
        when(mailRecords.searchList(any(FlexQuery.class))).thenAnswer(invocation -> {
            scannedIn.add(ContextHolder.getContext());
            return scannedIn.size() == 1 ? List.of(record) : List.of();
        });
        when(mailRecords.markSending(5L, 3L)).thenReturn(true);

        poller.poll();

        verify(mailRecords, timeout(2000).times(2)).searchList(any(FlexQuery.class));
        assertThat(scannedIn).hasSize(2).allSatisfy(ctx -> {
            assertThat(ctx.isCrossTenant()).isTrue();
            assertThat(ctx.isSkipPermissionCheck()).isTrue();
        });
    }

    @Test
    void itNeverClaimsMoreThanItHasIdleWorkers() throws InterruptedException {
        properties.getMail().setSendConcurrency(1);
        poller.destroy();
        poller = newPoller();
        when(mailRecords.searchList(any(FlexQuery.class))).thenReturn(List.of(due(5L), due(6L)), List.of());
        when(mailRecords.markSending(anyLong(), eq(3L))).thenReturn(true);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(mailProcessor).deliver(any(), anyLong());

        poller.poll();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        // The only worker is busy: the other record is left due, unclaimed.
        verify(mailRecords, times(1)).markSending(anyLong(), eq(3L));
        // ... and the next poll does not even look while no worker is idle.
        poller.poll();
        ArgumentCaptor<FlexQuery> queries = ArgumentCaptor.forClass(FlexQuery.class);
        verify(mailRecords).searchList(queries.capture());
        // A few candidates per idle worker, so a claim lost to another instance has somewhere to go.
        assertThat(queries.getValue().getLimitSize()).isEqualTo(DeliveryPoller.CANDIDATES_PER_WORKER);
        release.countDown();
    }

    @Test
    void nothingNewIsClaimedOnceShuttingDown() throws InterruptedException {
        poller.destroy();

        poller.poll();

        verify(mailRecords, never()).searchList(any(FlexQuery.class));
    }
}
