package io.softa.starter.message.shared.delivery;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.impl.MailDeliveryProcessor;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.impl.SmsDeliveryProcessor;

/**
 * Drives delivery. Each channel has a worker pool of {@code softa.message.mail.send-concurrency} /
 * {@code softa.message.sms.send-concurrency} threads per instance. A worker that finishes a send claims
 * the next due record — PENDING, or RETRY whose {@code nextRetryAt} has passed — straight away, so a
 * backlog drains at the pool's pace: concurrency ÷ time per send. A worker goes idle only when nothing is
 * due; every {@code softa.message.delivery.poll-interval-ms} the poll hands newly due records to idle
 * workers. A record is claimed only by a worker about to send it, so a claimed record never waits.
 * <p>
 * The send record is the queue: there is no outbox table and no broker in between. The claim is the
 * record's optimistic lock (PENDING/RETRY → SENDING), so workers and instances reading the same table
 * never send a record twice. Each scan reads a few due records per worker it is filling and tries them
 * in random order, so a claim lost to another worker or instance moves on to the next record instead of
 * leaving the worker idle. A record whose instance dies after claiming it stays SENDING until the
 * {@code ZombieRecordSweeper} revives it.
 * <p>
 * The scan reads every tenant's records; each claim and each delivery runs in the record's own tenant
 * context, under the trace id of the request that submitted it, with permission checks skipped
 * (system work).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "softa.message.delivery.enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryPoller implements DisposableBean {

    /** Due records a scan reads per worker: enough that a lost claim has somewhere else to go. */
    static final int CANDIDATES_PER_WORKER = 4;

    private final MailSendRecordService mailRecords;

    private final SmsSendRecordService smsRecords;

    private final MailDeliveryProcessor mailProcessor;

    private final SmsDeliveryProcessor smsProcessor;

    private final Lane mail;

    private final Lane sms;

    public DeliveryPoller(MailSendRecordService mailRecords, SmsSendRecordService smsRecords,
                          MailDeliveryProcessor mailProcessor, SmsDeliveryProcessor smsProcessor,
                          MessageProperties properties) {
        this.mailRecords = mailRecords;
        this.smsRecords = smsRecords;
        this.mailProcessor = mailProcessor;
        this.smsProcessor = smsProcessor;
        this.mail = new Lane("mail", properties.getMail().getSendConcurrency(), this::dueMail);
        this.sms = new Lane("sms", properties.getSms().getSendConcurrency(), this::dueSms);
    }

    /** Hands newly due records to idle workers; busy workers find their next record themselves. */
    @Scheduled(fixedDelayString = "${softa.message.delivery.poll-interval-ms:500}")
    public void poll() {
        mail.fill();
        sms.fill();
    }

    private List<Candidate> dueMail(int limit) {
        Filters due = Filters.or(
                new Filters().eq(MailSendRecord::getStatus, MailSendStatus.PENDING),
                new Filters().eq(MailSendRecord::getStatus, MailSendStatus.RETRY)
                        .le(MailSendRecord::getNextRetryAt, LocalDateTime.now()));
        FlexQuery query = new FlexQuery(due, Orders.ofAsc(MailSendRecord::getId));
        query.setLimitSize(limit);
        List<Candidate> candidates = new ArrayList<>();
        for (MailSendRecord record : scan(() -> mailRecords.searchList(query))) {
            long version = record.getVersion() != null ? record.getVersion() : 0L;
            Context ctx = recordContext(record.getTenantId(), record.getTraceId());
            candidates.add(new Candidate(
                    () -> ContextHolder.callWith(ctx, () -> mailRecords.markSending(record.getId(), version)),
                    () -> ContextHolder.runWith(ctx, () -> mailProcessor.deliver(record, version + 1))));
        }
        return candidates;
    }

    private List<Candidate> dueSms(int limit) {
        Filters due = Filters.or(
                new Filters().eq(SmsSendRecord::getStatus, SmsSendStatus.PENDING),
                new Filters().eq(SmsSendRecord::getStatus, SmsSendStatus.RETRY)
                        .le(SmsSendRecord::getNextRetryAt, LocalDateTime.now()));
        FlexQuery query = new FlexQuery(due, Orders.ofAsc(SmsSendRecord::getId));
        query.setLimitSize(limit);
        List<Candidate> candidates = new ArrayList<>();
        for (SmsSendRecord record : scan(() -> smsRecords.searchList(query))) {
            long version = record.getVersion() != null ? record.getVersion() : 0L;
            Context ctx = recordContext(record.getTenantId(), record.getTraceId());
            candidates.add(new Candidate(
                    () -> ContextHolder.callWith(ctx, () -> smsRecords.markSending(record.getId(), version)),
                    () -> ContextHolder.runWith(ctx, () -> smsProcessor.deliver(record, version + 1))));
        }
        return candidates;
    }

    /** Reads due records across tenants, from the scheduler thread or from a worker alike. */
    private static <T> List<T> scan(Supplier<List<T>> read) {
        Context ctx = new Context();
        ctx.setCrossTenant(true);
        ctx.setSkipPermissionCheck(true);
        return ContextHolder.callWith(ctx, read::get);
    }

    /** A fresh system context for one record: its tenant, the submitting request's trace id. */
    private static Context recordContext(Long tenantId, String traceId) {
        Context ctx = new Context(traceId);
        if (tenantId != null) {
            ctx.setTenantId(tenantId);
        }
        ctx.setSkipPermissionCheck(true);
        return ctx;
    }

    /** Lets in-flight sends finish (bounded) when the application shuts down; no new claims start. */
    @Override
    public void destroy() throws InterruptedException {
        mail.shutdown();
        sms.shutdown();
    }

    /** One due record: its claim and its delivery, each running in the record's own context. */
    private record Candidate(BooleanSupplier claim, Runnable delivery) {
    }

    /**
     * One channel's worker pool. A worker holds a permit from its first claim until it finds nothing
     * left to claim, so the pool never runs more sends than it has workers.
     */
    private static final class Lane {

        private static final long SHUTDOWN_GRACE_SECONDS = 30;

        private final String channel;

        private final Semaphore slots;

        private final ExecutorService pool;

        private final IntFunction<List<Candidate>> due;

        private volatile boolean stopping;

        Lane(String channel, int concurrency, IntFunction<List<Candidate>> due) {
            int size = Math.max(1, concurrency);
            this.channel = channel;
            this.slots = new Semaphore(size);
            this.pool = Executors.newFixedThreadPool(size, threadFactory("softa-" + channel + "-send-"));
            this.due = due;
        }

        /** The poll: give every idle worker a due record. Reads nothing while no worker is idle. */
        void fill() {
            int idle = slots.availablePermits();
            if (idle == 0 || stopping) {
                return;
            }
            for (Candidate candidate : candidates(idle * CANDIDATES_PER_WORKER)) {
                if (!slots.tryAcquire()) {
                    return;
                }
                if (!start(candidate)) {
                    slots.release();
                }
            }
        }

        /** Claims the record and hands it to a worker; true once a worker owns it, permit included. */
        private boolean start(Candidate candidate) {
            try {
                if (!candidate.claim().getAsBoolean()) {
                    return false;
                }
            } catch (RuntimeException e) {
                log.error("DeliveryPoller: could not claim a {} record", channel, e);
                return false;
            }
            try {
                pool.execute(() -> work(candidate));
                return true;
            } catch (RejectedExecutionException e) {
                // Shutting down after the claim: the record stays SENDING until the zombie sweeper revives it.
                log.warn("DeliveryPoller: {} pool is shutting down; a claimed record waits for the sweeper", channel);
                return false;
            }
        }

        /** Sends, then goes straight on to the next due record; releases the permit once none is due. */
        private void work(Candidate first) {
            try {
                Candidate current = first;
                while (current != null) {
                    deliver(current);
                    current = stopping ? null : claimNext();
                }
            } finally {
                slots.release();
            }
        }

        private void deliver(Candidate candidate) {
            try {
                candidate.delivery().run();
            } catch (RuntimeException e) {
                // The record stays SENDING; the zombie sweeper revives it.
                log.error("DeliveryPoller: {} delivery failed unexpectedly", channel, e);
            }
        }

        /** The next due record this worker won, or null when nothing is due (or every claim was lost). */
        private Candidate claimNext() {
            try {
                for (Candidate candidate : candidates(CANDIDATES_PER_WORKER)) {
                    if (candidate.claim().getAsBoolean()) {
                        return candidate;
                    }
                }
            } catch (RuntimeException e) {
                log.error("DeliveryPoller: could not claim the next {} record", channel, e);
            }
            return null;
        }

        /** Due records, oldest first, tried in random order so workers do not all race for the oldest. */
        private List<Candidate> candidates(int limit) {
            List<Candidate> candidates = new ArrayList<>(due.apply(limit));
            Collections.shuffle(candidates);
            return candidates;
        }

        void shutdown() throws InterruptedException {
            stopping = true;
            pool.shutdown();
            if (!pool.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("DeliveryPoller: {} sends still running after {}s at shutdown; "
                        + "their records stay SENDING until the zombie sweeper revives them",
                        channel, SHUTDOWN_GRACE_SECONDS);
                pool.shutdownNow();
            }
        }

        private static ThreadFactory threadFactory(String prefix) {
            AtomicInteger sequence = new AtomicInteger();
            return runnable -> {
                Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            };
        }
    }
}
