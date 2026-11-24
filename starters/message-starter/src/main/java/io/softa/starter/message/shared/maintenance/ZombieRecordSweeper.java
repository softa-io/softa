package io.softa.starter.message.shared.maintenance;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.message.shared.TenantScopes;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.smtp.SmtpMailTransport;
import io.softa.starter.message.shared.ErrorCategory;
import io.softa.starter.message.shared.ErrorClassifier;
import io.softa.starter.message.shared.retry.ExponentialBackoffPolicy;
import io.softa.starter.message.shared.retry.SendFailureHandler;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;

/**
 * Recovers records stuck in {@code SENDING} past the safe-send window.
 * <p>
 * A record enters {@code SENDING} when the delivery loop CAS-claims it. If the
 * JVM crashes between {@code SENDING} and the terminal CAS, the row stays locked
 * forever without this sweep.
 * <p>
 * Strategy: every minute, find records in {@code SENDING} whose
 * {@code updated_time} is older than {@link #STALE_AFTER} (five minutes — far
 * longer than any send, whose connect and read timeouts are seconds) and
 * transition them back to {@code RETRY} with {@code next_retry_at = now}, which
 * makes them due for the delivery loop again.
 * <p>
 * The window holds only while every send finishes well inside it, so the sweeper
 * refuses to start when a channel's transport timeouts would let one run past it
 * (see the constructor): a send that is only slow would otherwise be revived while
 * still in flight and sent twice.
 * <p>
 * An SMS is not revived. A text stuck in {@code SENDING} may already be on the phone — the provider
 * accepted it and only the bookkeeping failed, or the call was cut off after it arrived — and a second
 * copy (a second sign-in code) is worse than a missing one, which shows as FAILED and is Retried by hand.
 * The sweep fails it as {@link ErrorClassifier#OUTCOME_UNKNOWN} through the failure handler, which logs
 * it at ERROR. Mail is revived as before.
 * <p>
 * Each revival counts as a retry, and the budget is checked here too: a record
 * whose retries are already used up is dead-lettered instead of revived. An
 * attempt that never finishes (a crash or hang on every try) never reaches the
 * failure handler that normally enforces the budget, so without this check such
 * a record would be revived every stale window, forever.
 * <p>
 * Safety comes from the framework {@code versionLock}; a concurrent legitimate
 * completion wins and this sweep skips the stale snapshot.
 */
@Slf4j
@Component
public class ZombieRecordSweeper {

    /** A {@code SENDING} record untouched this long is stale. */
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    /**
     * The most a channel's connection timeout and read timeout may add up to: half of {@link #STALE_AFTER}.
     * An attempt is its preparation, a connection and a few reads — an SMTP exchange several, an SMS call
     * one — so half the window leaves room for all of them.
     */
    static final Duration MAX_CONNECT_AND_READ = STALE_AFTER.dividedBy(2);

    private static final int BATCH_LIMIT = 200;
    private static final String ZOMBIE_CODE = "ZOMBIE_RECOVERED";
    private static final String ZOMBIE_MESSAGE = "Revived after stale SENDING";
    private static final String EXHAUSTED_CODE = "ZOMBIE_EXHAUSTED";
    private static final String EXHAUSTED_MESSAGE =
            "Stale SENDING with the retry budget used up; not revived again";
    private static final String STUCK_SMS_MESSAGE =
            "Stuck in SENDING: the provider may already have sent it; check before Retrying";

    private final MailSendRecordService mailSendRecordService;
    private final SmsSendRecordService smsSendRecordService;
    private final SendFailureHandler sendFailureHandler;

    /** Refuses to start when a channel's transport timeouts are not well inside {@link #STALE_AFTER}. */
    public ZombieRecordSweeper(MailSendRecordService mailSendRecordService,
                               SmsSendRecordService smsSendRecordService,
                               SendFailureHandler sendFailureHandler,
                               MessageProperties messageProperties) {
        requireWithinStaleWindow("mail", messageProperties.getMail().getTransport());
        requireWithinStaleWindow("sms", messageProperties.getSms().getTransport());
        this.mailSendRecordService = mailSendRecordService;
        this.smsSendRecordService = smsSendRecordService;
        this.sendFailureHandler = sendFailureHandler;
    }

    /**
     * Both timeouts set and positive — zero is no timeout, and a send that hangs would outlast any window —
     * and together at most {@link #MAX_CONNECT_AND_READ}.
     */
    static void requireWithinStaleWindow(String channel, MessageProperties.Transport transport) {
        String keys = "softa.message." + channel + ".transport";
        Duration connect = transport.getConnectionTimeout();
        Duration read = transport.getReadTimeout();
        if (connect == null || read == null || !connect.isPositive() || !read.isPositive()) {
            throw new IllegalStateException(keys + ": connection-timeout and read-timeout must both be set and "
                    + "positive. Zero means no timeout: a send that hangs would outlast the "
                    + STALE_AFTER.toMinutes() + "-minute window after which a SENDING record is sent again.");
        }
        Duration total = connect.plus(read);
        if (total.compareTo(MAX_CONNECT_AND_READ) > 0) {
            throw new IllegalStateException(String.format("%s: connection-timeout + read-timeout is %ds, more than "
                            + "%ds, half the %d-minute window after which a SENDING record is taken for a crashed "
                            + "send and sent again. Lower the timeouts.",
                    keys, total.toSeconds(), MAX_CONNECT_AND_READ.toSeconds(), STALE_AFTER.toMinutes()));
        }
    }

    /**
     * The scan is {@code @CrossTenant} (one sweeper revives every tenant's
     * records), but each revive runs inside the record's own tenant context,
     * where the versioned update finds its row.
     */
    @Scheduled(cron = "0 * * * * *")
    @CrossTenant
    public void sweep() {
        LocalDateTime threshold = LocalDateTime.now().minus(STALE_AFTER);
        int mail = sweepMail(threshold);
        sweepSms(threshold);
        if (mail > 0) {
            log.info("ZombieRecordSweeper: revived mail={}", mail);
        }
    }

    private int sweepMail(LocalDateTime threshold) {
        List<MailSendRecord> rows = mailSendRecordService.searchList(mailQuery(threshold));
        int revived = 0;
        for (MailSendRecord row : rows) {
            int retries = attempts(row.getRetryCount());
            if (retries >= ExponentialBackoffPolicy.MAX_ATTEMPTS) {
                inTenantContext(row.getTenantId(), () -> sendFailureHandler.deadLetter(
                        "mail", SmtpMailTransport.NAME, row.getId(), version(row.getVersion()), retries,
                        EXHAUSTED_CODE, EXHAUSTED_MESSAGE, ErrorCategory.UNKNOWN,
                        mailSendRecordService::markDeadLetter));
                continue;
            }
            boolean ok = inTenantContext(row.getTenantId(),
                    () -> mailSendRecordService.markRetry(row.getId(), version(row.getVersion()),
                            ZOMBIE_CODE, ZOMBIE_MESSAGE, LocalDateTime.now()));
            if (ok) revived++;
        }
        return revived;
    }

    /** Fail every stale SMS record instead of reviving it (see the class comment). */
    private void sweepSms(LocalDateTime threshold) {
        List<SmsSendRecord> rows = smsSendRecordService.searchList(smsQuery(threshold));
        for (SmsSendRecord row : rows) {
            String provider = row.getProviderType() != null
                    ? row.getProviderType().name().toLowerCase() : null;
            inTenantContext(row.getTenantId(), () -> {
                sendFailureHandler.handle("sms", provider, row.getId(), version(row.getVersion()),
                        attempts(row.getRetryCount()), ErrorClassifier.OUTCOME_UNKNOWN, STUCK_SMS_MESSAGE,
                        new SendFailureHandler.RecordTransitions(smsSendRecordService::markRetry,
                                smsSendRecordService::markFailed, smsSendRecordService::markDeadLetter));
                return true;
            });
        }
    }

    /** Run one revive or dead-letter inside the record's tenant context (see {@link #sweep()}). */
    private boolean inTenantContext(Long tenantId, BooleanSupplier write) {
        Context ctx = ContextHolder.cloneContext();
        ctx.setTenantId(tenantId != null ? tenantId : TenantScopes.PLATFORM);
        ctx.setCrossTenant(false);
        ctx.setSkipPermissionCheck(true);
        boolean[] ok = new boolean[1];
        ContextHolder.runWith(ctx, () -> ok[0] = write.getAsBoolean());
        return ok[0];
    }

    private FlexQuery mailQuery(LocalDateTime threshold) {
        Filters filters = new Filters()
                .eq(MailSendRecord::getStatus, MailSendStatus.SENDING)
                .lt(MailSendRecord::getUpdatedTime, threshold);
        FlexQuery query = new FlexQuery(filters, Orders.ofAsc(MailSendRecord::getUpdatedTime));
        query.setLimitSize(BATCH_LIMIT);
        return query;
    }

    private FlexQuery smsQuery(LocalDateTime threshold) {
        Filters filters = new Filters()
                .eq(SmsSendRecord::getStatus, SmsSendStatus.SENDING)
                .lt(SmsSendRecord::getUpdatedTime, threshold);
        FlexQuery query = new FlexQuery(filters, Orders.ofAsc(SmsSendRecord::getUpdatedTime));
        query.setLimitSize(BATCH_LIMIT);
        return query;
    }

    private static long version(Long version) {
        return version != null ? version : 0L;
    }

    private static int attempts(Integer attempts) {
        return attempts != null ? attempts : 0;
    }
}
