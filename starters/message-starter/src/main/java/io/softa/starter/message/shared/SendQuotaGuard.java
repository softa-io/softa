package io.softa.starter.message.shared;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.message.quota.service.TenantMessageQuotaService;
import io.softa.starter.message.quota.service.TenantMessageUsageService;

/**
 * Send-volume quota gate, charged when a send record is <b>prepared</b> — its first delivery attempt,
 * after the template rendered and the server or provider resolved, so a send that cannot go out costs
 * nothing. Each bucket has two ceilings, both commercial limits rather than rate limiting: an over-quota
 * send fails its record with {@code DAILY_QUOTA_EXCEEDED} or {@code MONTHLY_QUOTA_EXCEEDED}, and nothing
 * queues, delays or retries. Retries of a prepared record never touch either count, so one message costs
 * exactly one unit however many SMTP / provider attempts it takes.
 * <ul>
 *   <li><b>Monthly</b> — the tenant's allowance. Counters live in the database — one
 *       {@code TenantMessageUsage} row per bucket per calendar month (server default zone), incremented
 *       via the ORM's optimistic-lock CAS — so per-month history is durable and queryable as a plain
 *       model, with the ceiling in force snapshotted onto each row. There is no reset job: a new month
 *       simply starts a new row.</li>
 *   <li><b>Daily</b> — how much of one day a tenant may take. The platform's servers and providers are
 *       shared, and their own limits ({@code dailySendLimit}) count every tenant together, so without a
 *       day of its own one tenant's burst could leave the others nothing until midnight. Counted in Redis
 *       per bucket per calendar day (server default zone). It is checked first, so a send the day refuses
 *       costs the month nothing, and a send the month then refuses is handed back to the day. A Redis
 *       flush forgets the day's count.</li>
 * </ul>
 * The bucket is the tenant the send record is filed under, whatever its {@link MessageScope}: a send
 * made for a tenant — its own mail, or a platform notice about one of its accounts — draws on that
 * tenant's quota. A send no tenant can be named for draws on the platform's own ({@code tenantId = -1}),
 * whose ceilings are expected to be very large and exist to cap runaway or malicious mass sending.
 * <p>
 * Limits resolve per send from {@code TenantMessageQuota} rows with {@code softa.message.quota.*}
 * deployment defaults (an operations change takes effect immediately); a null resolved limit means
 * unlimited — the counts still advance, so usage stays reportable.
 */
@Slf4j
@Component
public class SendQuotaGuard {

    /** Long enough to outlive the day it counts, whatever zone the servers run in. */
    private static final long DAILY_TTL_SECONDS = 2 * 86_400L;

    /** Counts one send unless the cap in ARGV[1] (negative = none) is reached: the new count, or -1. */
    private static final DefaultRedisScript<Long> DAILY_ACQUIRE = new DefaultRedisScript<>("""
            local limit = tonumber(ARGV[1])
            if limit >= 0 then
              local used = tonumber(redis.call('GET', KEYS[1]) or '0')
              if used >= limit then
                return -1
              end
            end
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            end
            return count
            """, Long.class);

    /** Hands back one counted send, never going below zero. */
    private static final DefaultRedisScript<Long> DAILY_RELEASE = new DefaultRedisScript<>("""
            local used = tonumber(redis.call('GET', KEYS[1]) or '0')
            if used > 0 then
              return redis.call('DECR', KEYS[1])
            end
            return 0
            """, Long.class);

    @Autowired
    private TenantMessageQuotaService quotaService;

    @Autowired
    private TenantMessageUsageService usageService;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * The quota bucket a send consumes: the tenant its record is filed under, the platform's own for
     * a record filed under none.
     */
    public static long bucketOf(Long recordTenantId) {
        return recordTenantId != null ? recordTenantId : TenantScopes.PLATFORM;
    }

    /**
     * Charge one send of {@code channel} ("mail" / "sms") to the bucket's day and month, or refuse it.
     *
     * @throws SendRejection {@code DAILY_QUOTA_EXCEEDED} when today's cap is reached,
     *         {@code MONTHLY_QUOTA_EXCEEDED} when the month's limit is
     * @throws io.softa.framework.base.exception.VersionException when concurrent sends of the bucket kept
     *         winning the monthly counter's race (transient; the day is handed back)
     */
    public void consume(String channel, long bucketTenantId) {
        TenantMessageQuotaService.ResolvedLimits limits = quotaService.resolveLimits(bucketTenantId);
        boolean mail = "mail".equals(channel);
        Long dailyLimit = mail ? limits.mailDailyLimit() : limits.smsDailyLimit();
        String dayKey = dailyKey(channel, bucketTenantId, LocalDate.now());
        Long counted = stringRedisTemplate.execute(DAILY_ACQUIRE, List.of(dayKey),
                String.valueOf(dailyLimit != null ? dailyLimit : -1L), String.valueOf(DAILY_TTL_SECONDS));
        if (counted != null && counted < 0) {
            throw new SendRejection(ErrorClassifier.DAILY_QUOTA_EXCEEDED, "Daily " + channel
                    + " send quota (" + dailyLimit + ") is used up for today; it starts again tomorrow. "
                    + "Contact platform operations to raise the limit.");
        }
        try {
            usageService.consume(channel, bucketTenantId, mail ? limits.mailMonthlyLimit() : limits.smsMonthlyLimit());
        } catch (BusinessException monthUsedUp) {
            release(dayKey);
            throw new SendRejection(ErrorClassifier.MONTHLY_QUOTA_EXCEEDED, monthUsedUp.getMessage());
        } catch (RuntimeException e) {
            // Not charged to the month (a lost counter race, a database hiccup), so not to the day either.
            release(dayKey);
            throw e;
        }
    }

    /** Today's charged-send count for one bucket (0 when nothing was charged). */
    public long usedToday(String channel, long tenantId) {
        String value = stringRedisTemplate.opsForValue().get(dailyKey(channel, tenantId, LocalDate.now()));
        return value != null ? Long.parseLong(value) : 0L;
    }

    /** The month's charged-send count for one bucket (0 when never counted). */
    public long usage(String channel, long tenantId, YearMonth month) {
        return usageService.findByBucketAndMonth(tenantId, month)
                .map(row -> "mail".equals(channel) ? row.getMailUsed() : row.getSmsUsed())
                .map(count -> count != null ? count : 0L)
                .orElse(0L);
    }

    private void release(String dayKey) {
        try {
            stringRedisTemplate.execute(DAILY_RELEASE, List.of(dayKey));
        } catch (RuntimeException e) {
            // The refusal that brought us here is what the record must show; a day counted one too high
            // only means the cap is reached one send early.
            log.warn("SendQuotaGuard: could not hand back a daily count for {}: {}", dayKey, e.getMessage());
        }
    }

    private String dailyKey(String channel, long tenantId, LocalDate day) {
        return cacheService.getKeyPath("quota:" + channel + ":daily:" + tenantId + ":" + day);
    }
}
