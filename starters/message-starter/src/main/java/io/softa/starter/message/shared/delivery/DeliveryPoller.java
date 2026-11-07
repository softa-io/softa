package io.softa.starter.message.shared.delivery;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.annotation.CrossTenant;
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
 * Drives delivery. Every {@code softa.message.delivery.poll-interval-ms} it claims the mail and SMS
 * send records that are due — PENDING, or RETRY whose {@code nextRetryAt} has passed — across tenants,
 * and runs each on its channel's worker pool: {@code softa.message.mail.send-concurrency} /
 * {@code softa.message.sms.send-concurrency} threads per instance. It claims only as many records as
 * the pool has free workers, so a claimed record never waits in a queue; the rest stay due for the
 * next poll, or for another instance.
 * <p>
 * The send record is the queue: there is no outbox table and no broker in between. The claim is the
 * record's optimistic lock (PENDING/RETRY → SENDING), so instances polling the same table never send a
 * record twice. A record whose instance dies after claiming it stays SENDING until the
 * {@code ZombieRecordSweeper} revives it.
 * <p>
 * Each claim and each delivery runs in the record's own tenant context, under the trace id of the
 * request that submitted it, with permission checks skipped (system work).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "softa.message.delivery.enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryPoller implements DisposableBean {

    private final MailSendRecordService mailRecords;

    private final SmsSendRecordService smsRecords;

    private final MailDeliveryProcessor mailProcessor;

    private final SmsDeliveryProcessor smsProcessor;

    private final Workers mailWorkers;

    private final Workers smsWorkers;

    public DeliveryPoller(MailSendRecordService mailRecords, SmsSendRecordService smsRecords,
                          MailDeliveryProcessor mailProcessor, SmsDeliveryProcessor smsProcessor,
                          MessageProperties properties) {
        this.mailRecords = mailRecords;
        this.smsRecords = smsRecords;
        this.mailProcessor = mailProcessor;
        this.smsProcessor = smsProcessor;
        this.mailWorkers = new Workers("mail", properties.getMail().getSendConcurrency());
        this.smsWorkers = new Workers("sms", properties.getSms().getSendConcurrency());
    }

    /** The scan is cross-tenant; every claim and delivery switches into its record's tenant. */
    @Scheduled(fixedDelayString = "${softa.message.delivery.poll-interval-ms:500}")
    @CrossTenant
    public void poll() {
        pollMail();
        pollSms();
    }

    private void pollMail() {
        int free = mailWorkers.free();
        if (free == 0) {
            return;
        }
        Filters due = Filters.or(
                new Filters().eq(MailSendRecord::getStatus, MailSendStatus.PENDING),
                new Filters().eq(MailSendRecord::getStatus, MailSendStatus.RETRY)
                        .le(MailSendRecord::getNextRetryAt, LocalDateTime.now()));
        FlexQuery query = new FlexQuery(due, Orders.ofAsc(MailSendRecord::getId));
        query.setLimitSize(free);
        for (MailSendRecord record : mailRecords.searchList(query)) {
            long version = record.getVersion() != null ? record.getVersion() : 0L;
            Context ctx = recordContext(record.getTenantId(), record.getTraceId());
            mailWorkers.claimAndRun(
                    () -> ContextHolder.callWith(ctx, () -> mailRecords.markSending(record.getId(), version)),
                    () -> ContextHolder.runWith(ctx, () -> mailProcessor.deliver(record, version + 1)));
        }
    }

    private void pollSms() {
        int free = smsWorkers.free();
        if (free == 0) {
            return;
        }
        Filters due = Filters.or(
                new Filters().eq(SmsSendRecord::getStatus, SmsSendStatus.PENDING),
                new Filters().eq(SmsSendRecord::getStatus, SmsSendStatus.RETRY)
                        .le(SmsSendRecord::getNextRetryAt, LocalDateTime.now()));
        FlexQuery query = new FlexQuery(due, Orders.ofAsc(SmsSendRecord::getId));
        query.setLimitSize(free);
        for (SmsSendRecord record : smsRecords.searchList(query)) {
            long version = record.getVersion() != null ? record.getVersion() : 0L;
            Context ctx = recordContext(record.getTenantId(), record.getTraceId());
            smsWorkers.claimAndRun(
                    () -> ContextHolder.callWith(ctx, () -> smsRecords.markSending(record.getId(), version)),
                    () -> ContextHolder.runWith(ctx, () -> smsProcessor.deliver(record, version + 1)));
        }
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

    /** Lets in-flight sends finish (bounded) when the application shuts down. */
    @Override
    public void destroy() throws InterruptedException {
        mailWorkers.shutdown();
        smsWorkers.shutdown();
    }

    /** One channel's worker pool, with a permit per worker so a poll never claims more than it can run. */
    private static final class Workers {

        private static final long SHUTDOWN_GRACE_SECONDS = 30;

        private final String channel;

        private final Semaphore slots;

        private final ExecutorService pool;

        Workers(String channel, int concurrency) {
            int size = Math.max(1, concurrency);
            this.channel = channel;
            this.slots = new Semaphore(size);
            this.pool = Executors.newFixedThreadPool(size, threadFactory("softa-" + channel + "-send-"));
        }

        int free() {
            return slots.availablePermits();
        }

        /**
         * Claim a record and, only if the claim wins, run its delivery on a worker. The permit is held
         * from the claim until the delivery ends.
         */
        void claimAndRun(BooleanSupplier claim, Runnable delivery) {
            if (!slots.tryAcquire()) {
                return;
            }
            boolean running = false;
            try {
                if (!claim.getAsBoolean()) {
                    return;
                }
                pool.execute(() -> {
                    try {
                        delivery.run();
                    } catch (RuntimeException e) {
                        // The record stays SENDING; the zombie sweeper revives it.
                        log.error("DeliveryPoller: {} delivery failed unexpectedly", channel, e);
                    } finally {
                        slots.release();
                    }
                });
                running = true;
            } catch (RuntimeException e) {
                log.error("DeliveryPoller: could not claim or start a {} delivery", channel, e);
            } finally {
                if (!running) {
                    slots.release();
                }
            }
        }

        void shutdown() throws InterruptedException {
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
