package io.softa.starter.message.shared.delivery;

import java.time.LocalDateTime;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
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
 * worker, in the record's own tenant and trace. A scan is two reads, PENDING and due RETRY, each in
 * the order of its own index.
 */
class DeliveryPollerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 10, 12, 0);

    private MailSendRecordService mailRecords;
    private MailDeliveryProcessor mailProcessor;
    private MessageProperties properties;
    private DeliveryPoller poller;

    /** What the PENDING reads return, one list per read, then nothing. */
    private final Deque<List<MailSendRecord>> pendingReads = new ConcurrentLinkedDeque<>();
    /** What the due-RETRY reads return, one list per read, then nothing. */
    private final Deque<List<MailSendRecord>> retryReads = new ConcurrentLinkedDeque<>();
    /** The context of every read, in call order. */
    private final List<Context> readContexts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        mailRecords = mock(MailSendRecordService.class);
        when(mailRecords.searchList(any(FlexQuery.class))).thenAnswer(invocation -> {
            readContexts.add(ContextHolder.getContext());
            List<MailSendRecord> next = (isRetryRead(invocation.getArgument(0)) ? retryReads : pendingReads).poll();
            return next != null ? next : List.of();
        });
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

    private static boolean isRetryRead(FlexQuery query) {
        return query.getOrders().toString().startsWith("nextRetryAt");
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

    private static MailSendRecord pendingSince(long id, int minutes) {
        MailSendRecord r = due(id);
        r.setUpdatedTime(T0.plusMinutes(minutes));
        return r;
    }

    private static MailSendRecord retryDueSince(long id, int minutes) {
        MailSendRecord r = due(id);
        r.setStatus(MailSendStatus.RETRY);
        r.setNextRetryAt(T0.plusMinutes(minutes));
        return r;
    }

    @Test
    void aClaimedRecordIsDeliveredInItsTenantAndTrace() {
        MailSendRecord record = due(5L);
        pendingReads.add(List.of(record));
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
    void aScanIsTwoReadsThatEachFollowTheirStatusIndex() {
        // One query with "status = ? OR (…)" ordered by id follows neither index: PostgreSQL's generic
        // plan walks the primary key through the whole send log. Each read names one status and orders
        // by that status index's second column, so it reads only the rows it returns.
        poller.poll();

        ArgumentCaptor<FlexQuery> reads = ArgumentCaptor.forClass(FlexQuery.class);
        verify(mailRecords, times(2)).searchList(reads.capture());
        FlexQuery pending = reads.getAllValues().stream().filter(q -> !isRetryRead(q)).findFirst().orElseThrow();
        FlexQuery retries = reads.getAllValues().stream().filter(DeliveryPollerTest::isRetryRead).findFirst().orElseThrow();
        assertThat(pending.getFilters().toString())
                .contains("[\"status\",\"=\",\"Pending\"]").doesNotContain("Retry").doesNotContain("\"OR\"");
        assertThat(pending.getOrders().toString()).isEqualTo("updatedTime ASC");
        assertThat(retries.getFilters().toString())
                .contains("[\"status\",\"=\",\"Retry\"]").contains("nextRetryAt").doesNotContain("\"OR\"");
        assertThat(retries.getOrders().toString()).isEqualTo("nextRetryAt ASC");
        // Four idle workers, a few candidates each, from either read.
        int limit = properties.getMail().getSendConcurrency() * DeliveryPoller.CANDIDATES_PER_WORKER;
        assertThat(pending.getLimitSize()).isEqualTo(limit);
        assertThat(retries.getLimitSize()).isEqualTo(limit);
    }

    @Test
    void theRecordsDueLongestAreHandedOutFromBothReads() throws InterruptedException {
        // One worker: a scan hands out four candidates. Every claim is lost, so the poll tries each one.
        properties.getMail().setSendConcurrency(1);
        poller.destroy();
        poller = newPoller();
        pendingReads.add(List.of(pendingSince(1L, 1), pendingSince(3L, 3), pendingSince(5L, 5)));
        retryReads.add(List.of(retryDueSince(2L, 2), retryDueSince(4L, 4), retryDueSince(6L, 6)));
        when(mailRecords.markSending(anyLong(), eq(3L))).thenReturn(false);

        poller.poll();

        for (long longestDue = 1L; longestDue <= 4L; longestDue++) {
            verify(mailRecords).markSending(longestDue, 3L);
        }
        verify(mailRecords, never()).markSending(5L, 3L);
        verify(mailRecords, never()).markSending(6L, 3L);
    }

    @Test
    void aClaimLostToAnotherInstanceIsNotDelivered() {
        pendingReads.add(List.of(due(5L)));
        when(mailRecords.markSending(5L, 3L)).thenReturn(false);

        poller.poll();

        verify(mailProcessor, after(200).never()).deliver(any(), anyLong());
    }

    @Test
    void aLostClaimMovesOnToTheNextDueRecord() {
        // Another instance won record 5; the worker takes record 6 in the same poll instead of idling.
        MailSendRecord taken = due(5L);
        MailSendRecord free = due(6L);
        pendingReads.add(List.of(taken, free));
        when(mailRecords.markSending(5L, 3L)).thenReturn(false);
        when(mailRecords.markSending(6L, 3L)).thenReturn(true);

        poller.poll();

        verify(mailProcessor, timeout(2000)).deliver(free, 4L);
        verify(mailProcessor, never()).deliver(taken, 4L);
    }

    @Test
    void aWorkerGoesStraightOnToTheNextDueRecord() {
        // One poll: the second record goes out as soon as the first is sent, not a poll later.
        MailSendRecord first = due(5L);
        MailSendRecord second = due(6L);
        pendingReads.add(List.of(first));
        pendingReads.add(List.of(second));
        when(mailRecords.markSending(anyLong(), eq(3L))).thenReturn(true);

        poller.poll();

        verify(mailProcessor, timeout(2000)).deliver(second, 4L);
        InOrder order = inOrder(mailProcessor);
        order.verify(mailProcessor).deliver(first, 4L);
        order.verify(mailProcessor).deliver(second, 4L);
        // The poll's scan, then one per finished send — two reads each; the last scan found nothing,
        // so the worker went idle.
        verify(mailRecords, timeout(2000).times(6)).searchList(any(FlexQuery.class));
    }

    @Test
    void everyScanReadsAcrossTenants() {
        // The worker's own scan runs on a pool thread with no request context: it must not be tenant-bound.
        pendingReads.add(List.of(due(5L)));
        when(mailRecords.markSending(5L, 3L)).thenReturn(true);

        poller.poll();

        // The poll's scan and the worker's after its send: two reads each.
        verify(mailRecords, timeout(2000).times(4)).searchList(any(FlexQuery.class));
        assertThat(readContexts).hasSize(4).allSatisfy(ctx -> {
            assertThat(ctx.isCrossTenant()).isTrue();
            assertThat(ctx.isSkipPermissionCheck()).isTrue();
        });
    }

    @Test
    void itNeverClaimsMoreThanItHasIdleWorkers() throws InterruptedException {
        properties.getMail().setSendConcurrency(1);
        poller.destroy();
        poller = newPoller();
        pendingReads.add(List.of(due(5L), due(6L)));
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
        // ... and the next poll does not even look while no worker is idle: the first poll's two reads
        // are all there are.
        poller.poll();
        ArgumentCaptor<FlexQuery> reads = ArgumentCaptor.forClass(FlexQuery.class);
        verify(mailRecords, times(2)).searchList(reads.capture());
        // A few candidates per idle worker, so a claim lost to another instance has somewhere to go.
        assertThat(reads.getAllValues()).allSatisfy(query ->
                assertThat(query.getLimitSize()).isEqualTo(DeliveryPoller.CANDIDATES_PER_WORKER));
        release.countDown();
    }

    @Test
    void nothingNewIsClaimedOnceShuttingDown() throws InterruptedException {
        poller.destroy();

        poller.poll();

        verify(mailRecords, never()).searchList(any(FlexQuery.class));
    }
}
