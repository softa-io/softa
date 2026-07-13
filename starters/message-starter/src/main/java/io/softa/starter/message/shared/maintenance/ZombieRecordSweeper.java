package io.softa.starter.message.shared.maintenance;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.mq.outbox.OutboxEntry;
import io.softa.starter.message.mq.outbox.OutboxRecordWriter;
import io.softa.starter.message.mq.outbox.OutboxService;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;

/**
 * Recovers records stuck in {@code SENDING} past the safe-send window.
 * <p>
 * A record enters {@code SENDING} when a consumer CAS-claims it. If the JVM
 * crashes between {@code SENDING} and the terminal CAS, the row stays locked
 * forever without this sweep.
 * <p>
 * Strategy: every minute, find records in {@code SENDING} whose
 * {@code updated_time} is older than {@code softa.message.zombie.stale-seconds}
 * (default 300 = 5 minutes), transition them back to {@code RETRY} with
 * {@code next_retry_at = now}, and enqueue the matching retry outbox row in
 * the same transaction.
 * <p>
 * It also reopens stale outbox {@code PUBLISHING} claims to {@code NEW}. Safety
 * comes from the framework {@code versionLock}; a concurrent legitimate
 * completion wins and this sweep skips the stale snapshot.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "softa.message.zombie.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ZombieRecordSweeper {

    private static final int BATCH_LIMIT = 200;
    private static final String ZOMBIE_CODE = "ZOMBIE_RECOVERED";
    private static final String ZOMBIE_MESSAGE = "Revived after stale SENDING";
    private static final String OUTBOX_ZOMBIE_MESSAGE = "Revived after stale PUBLISHING";

    @Autowired
    private MailSendRecordService mailSendRecordService;

    @Autowired
    private SmsSendRecordService smsSendRecordService;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxRecordWriter outboxRecordWriter;

    @Autowired
    private MessageProperties properties;

    /**
     * The scan is {@code @CrossTenant} (one sweeper revives every tenant's
     * records), but each revive runs inside the record's own tenant context so
     * the retry outbox payload carries the owning tenant — the retry consumer
     * restores it before re-driving the send.
     */
    @Scheduled(cron = "${softa.message.zombie.cron:0 * * * * *}")
    @CrossTenant
    public void sweep() {
        LocalDateTime threshold = LocalDateTime.now()
                .minusSeconds(properties.getZombie().getStaleSeconds());
        int mail = sweepMail(threshold);
        int sms = sweepSms(threshold);
        int outbox = sweepOutbox(threshold);
        if (mail + sms + outbox > 0) {
            log.info("ZombieRecordSweeper: revived mail={} sms={} outbox={}", mail, sms, outbox);
        }
    }

    private int sweepMail(LocalDateTime threshold) {
        List<MailSendRecord> rows = mailSendRecordService.searchList(mailQuery(threshold));
        int revived = 0;
        for (MailSendRecord row : rows) {
            LocalDateTime retryAt = LocalDateTime.now();
            boolean ok = reviveInTenantContext(row.getTenantId(),
                    () -> outboxRecordWriter.transitionAndEnqueueAt(
                            () -> mailSendRecordService.markRetry(row.getId(), version(row.getVersion()),
                                    ZOMBIE_CODE, ZOMBIE_MESSAGE, retryAt),
                            row.getId(), "MailSendRecord", TopicRoute.MAIL_SEND, retryAt));
            if (ok) revived++;
        }
        return revived;
    }

    private int sweepSms(LocalDateTime threshold) {
        List<SmsSendRecord> rows = smsSendRecordService.searchList(smsQuery(threshold));
        int revived = 0;
        for (SmsSendRecord row : rows) {
            LocalDateTime retryAt = LocalDateTime.now();
            boolean ok = reviveInTenantContext(row.getTenantId(),
                    () -> outboxRecordWriter.transitionAndEnqueueAt(
                            () -> smsSendRecordService.markRetry(row.getId(), version(row.getVersion()),
                                    ZOMBIE_CODE, ZOMBIE_MESSAGE, retryAt),
                            row.getId(), "SmsSendRecord", TopicRoute.SMS_SEND, retryAt));
            if (ok) revived++;
        }
        return revived;
    }

    /** Run one revive inside the record's tenant context (see {@link #sweep()}). */
    private boolean reviveInTenantContext(Long tenantId, BooleanSupplier revive) {
        Context ctx = ContextHolder.cloneContext();
        ctx.setTenantId(tenantId != null ? tenantId : 0L);
        ctx.setCrossTenant(false);
        ctx.setSkipPermissionCheck(true);
        boolean[] ok = new boolean[1];
        ContextHolder.runWith(ctx, () -> ok[0] = revive.getAsBoolean());
        return ok[0];
    }

    private int sweepOutbox(LocalDateTime threshold) {
        List<OutboxEntry> rows = outboxService.findStalePublishing(threshold, BATCH_LIMIT);
        int revived = 0;
        for (OutboxEntry row : rows) {
            if (outboxService.markNew(row.getId(), version(row.getVersion()), attempts(row.getAttempts()),
                    OUTBOX_ZOMBIE_MESSAGE, LocalDateTime.now())) {
                revived++;
            }
        }
        return revived;
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
