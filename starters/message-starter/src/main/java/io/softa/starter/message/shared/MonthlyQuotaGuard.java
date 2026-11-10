package io.softa.starter.message.shared;

import java.time.YearMonth;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.starter.message.quota.entity.TenantMessageUsage;
import io.softa.starter.message.quota.service.TenantMessageQuotaService;
import io.softa.starter.message.quota.service.TenantMessageUsageService;

/**
 * Monthly send-volume quota gate, charged when a send record is <b>prepared</b>
 * — its first delivery attempt, after the template rendered and the server or
 * provider resolved, so a send that cannot go out costs nothing. A commercial
 * ceiling, not rate limiting: an over-quota send fails its record with
 * {@code MONTHLY_QUOTA_EXCEEDED}; nothing queues, delays or retries. Retries
 * of a prepared record never touch the ledger, so one message costs exactly
 * one unit regardless of how many SMTP/provider attempts it takes.
 * <p>
 * The counting bucket follows the send's {@link MessageScope}: {@code TENANT}
 * sends draw on the current tenant's quota, {@code PLATFORM} sends on the
 * platform's own ({@code tenantId = -1}) — the platform ceiling is expected to
 * be very large and exists to cap runaway or malicious mass sending.
 * <p>
 * Counters live in the database — one {@code TenantMessageUsage} row per
 * bucket per calendar month (server default zone), incremented via the ORM's
 * optimistic-lock CAS — so per-month history is durable and queryable as a
 * plain model, with the ceiling in force snapshotted onto each row. Limits
 * resolve per send from {@code TenantMessageQuota} rows with
 * {@code softa.message.quota.*} deployment defaults (an operations change
 * takes effect immediately); a null resolved limit means unlimited — the
 * ledger still advances so usage stays reportable. There is no reset job:
 * a new month simply starts a new row.
 */
@Component
public class MonthlyQuotaGuard {

    @Autowired
    private TenantMessageQuotaService quotaService;

    @Autowired
    private TenantMessageUsageService usageService;

    /** The quota bucket a send consumes: the platform's own for PLATFORM scope. */
    public static long bucketFor(MessageScope scope) {
        return scope == MessageScope.PLATFORM
                ? TenantScopes.PLATFORM : TenantScopes.currentTenantOrPlatform();
    }

    /**
     * Consume one unit of the month's quota for {@code channel}
     * ("mail" / "sms") from {@code bucketTenantId}, or reject.
     *
     * @throws BusinessException when the bucket's monthly limit is exhausted
     */
    public void consume(String channel, long bucketTenantId) {
        TenantMessageQuotaService.ResolvedLimits limits = quotaService.resolveLimits(bucketTenantId);
        Long limit = "mail".equals(channel) ? limits.mailMonthlyLimit() : limits.smsMonthlyLimit();
        usageService.consume(channel, bucketTenantId, limit);
    }

    /** The month's charged-send count for one bucket (0 when never counted). */
    public long usage(String channel, long tenantId, YearMonth month) {
        return usageService.findByBucketAndMonth(tenantId, month)
                .map(row -> "mail".equals(channel) ? row.getMailUsed() : row.getSmsUsed())
                .map(count -> count != null ? count : 0L)
                .orElse(0L);
    }

    /** The full ledger row for one bucket and month, if any sends were charged. */
    public Optional<TenantMessageUsage> usageRow(long tenantId, YearMonth month) {
        return usageService.findByBucketAndMonth(tenantId, month);
    }
}
