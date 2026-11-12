package io.softa.starter.message.quota;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.VersionException;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.message.quota.entity.TenantMessageUsage;
import io.softa.starter.message.quota.service.TenantMessageQuotaService;
import io.softa.starter.message.quota.service.TenantMessageUsageService;
import io.softa.starter.message.shared.ErrorClassifier;
import io.softa.starter.message.shared.SendQuotaGuard;
import io.softa.starter.message.shared.SendRejection;
import io.softa.starter.message.shared.TenantScopes;

import static java.util.Optional.of;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SendQuotaGuard}: the bucket is the tenant the record is filed under; a send is charged to the
 * bucket's day, then its month, with the CURRENTLY resolved limits (quota row / deployment default) — so
 * an operations change takes effect immediately; and a send one of them refuses costs the other nothing.
 */
class SendQuotaGuardTest {

    private SendQuotaGuard guard;
    private TenantMessageQuotaService quotaService;
    private TenantMessageUsageService usageService;
    /** The day counters as Redis would hold them, by full key. */
    private final Map<String, Long> redis = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        guard = new SendQuotaGuard();
        quotaService = mock(TenantMessageQuotaService.class);
        usageService = mock(TenantMessageUsageService.class);
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.getKeyPath(anyString())).thenAnswer(inv -> "softa:" + inv.getArgument(0));
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        // The two scripts, run against the map: acquire counts unless the cap is reached, release hands
        // one back without going below zero.
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(inv -> {
            RedisScript<Long> script = inv.getArgument(0);
            String key = ((List<String>) inv.getArgument(1)).getFirst();
            long used = redis.getOrDefault(key, 0L);
            if (script.getScriptAsString().contains("INCR")) {
                long limit = Long.parseLong(inv.getArgument(2));
                if (limit >= 0 && used >= limit) {
                    return -1L;
                }
                redis.put(key, used + 1);
                return used + 1;
            }
            if (used > 0) {
                redis.put(key, used - 1);
            }
            return Math.max(used - 1, 0L);
        });
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(values.get(anyString())).thenAnswer(inv -> {
            Long count = redis.get(inv.<String>getArgument(0));
            return count != null ? String.valueOf(count) : null;
        });
        when(redisTemplate.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(guard, "quotaService", quotaService);
        ReflectionTestUtils.setField(guard, "usageService", usageService);
        ReflectionTestUtils.setField(guard, "cacheService", cacheService);
        ReflectionTestUtils.setField(guard, "stringRedisTemplate", redisTemplate);
    }

    private void limits(Long mailMonthly, Long smsMonthly, Long mailDaily, Long smsDaily) {
        when(quotaService.resolveLimits(5L)).thenReturn(
                new TenantMessageQuotaService.ResolvedLimits(mailMonthly, smsMonthly, mailDaily, smsDaily));
    }

    private static String todayKey(String channel) {
        return "softa:quota:" + channel + ":daily:5:" + LocalDate.now();
    }

    @Test
    void aSendDrawsOnTheBucketOfTheTenantItIsFiledUnder() {
        // Whatever its scope: a platform notice filed under tenant 5 is tenant 5's to pay for.
        Assertions.assertEquals(5L, SendQuotaGuard.bucketOf(5L));
    }

    @Test
    void aSendFiledUnderNoTenantDrawsOnThePlatformBucket() {
        Assertions.assertEquals(TenantScopes.PLATFORM, SendQuotaGuard.bucketOf(TenantScopes.PLATFORM));
        Assertions.assertEquals(TenantScopes.PLATFORM, SendQuotaGuard.bucketOf(null));
    }

    @Test
    void consumeChargesTheDayAndHandsTheChannelsMonthlyLimitToTheLedger() {
        limits(100L, 20L, 10L, 5L);

        guard.consume("mail", 5L);
        verify(usageService).consume("mail", 5L, 100L);

        guard.consume("sms", 5L);
        verify(usageService).consume("sms", 5L, 20L);

        Assertions.assertEquals(1L, redis.get(todayKey("mail")));
        Assertions.assertEquals(1L, redis.get(todayKey("sms")));
        Assertions.assertEquals(1L, guard.usedToday("mail", 5L));
    }

    @Test
    void aSendOverTodaysCapIsRefusedAndCostsTheMonthNothing() {
        limits(100L, 20L, 2L, 5L);
        guard.consume("mail", 5L);
        guard.consume("mail", 5L);

        SendRejection refused = Assertions.assertThrows(SendRejection.class, () -> guard.consume("mail", 5L));

        Assertions.assertEquals(ErrorClassifier.DAILY_QUOTA_EXCEEDED, refused.code());
        verify(usageService, times(2)).consume(eq("mail"), anyLong(), any());
        Assertions.assertEquals(2L, guard.usedToday("mail", 5L));
        // The other channel has a day of its own.
        guard.consume("sms", 5L);
        verify(usageService).consume("sms", 5L, 20L);
    }

    @Test
    void aZeroDailyCapRefusesEverySend() {
        limits(100L, 20L, 0L, 5L);

        SendRejection refused = Assertions.assertThrows(SendRejection.class, () -> guard.consume("mail", 5L));

        Assertions.assertEquals(ErrorClassifier.DAILY_QUOTA_EXCEEDED, refused.code());
        verify(usageService, never()).consume(anyString(), anyLong(), any());
    }

    @Test
    void aSendTheMonthRefusesIsHandedBackToTheDay() {
        limits(100L, 20L, 10L, 5L);
        doThrow(new BusinessException("Monthly mail send quota (100) is exhausted for this month."))
                .when(usageService).consume("mail", 5L, 100L);

        SendRejection refused = Assertions.assertThrows(SendRejection.class, () -> guard.consume("mail", 5L));

        Assertions.assertEquals(ErrorClassifier.MONTHLY_QUOTA_EXCEEDED, refused.code());
        Assertions.assertEquals(0L, guard.usedToday("mail", 5L));
    }

    @Test
    void aLostCounterRaceHandsTheDayBackAndStaysTransient() {
        limits(100L, 20L, 10L, 5L);
        doThrow(new VersionException("kept changing")).when(usageService).consume("mail", 5L, 100L);

        // Not a SendRejection: the record goes to RETRY and is charged again when it is prepared again.
        Assertions.assertThrows(VersionException.class, () -> guard.consume("mail", 5L));

        Assertions.assertEquals(0L, guard.usedToday("mail", 5L));
    }

    @Test
    void withNoDailyCapTheDayIsStillCounted() {
        limits(null, null, null, null);

        guard.consume("mail", 5L);
        guard.consume("mail", 5L);

        verify(usageService, times(2)).consume("mail", 5L, null);
        Assertions.assertEquals(2L, guard.usedToday("mail", 5L));
    }

    @Test
    void usageReadsTheLedgerRowPerChannel() {
        TenantMessageUsage row = new TenantMessageUsage();
        row.setMailUsed(37L);
        row.setSmsUsed(4L);
        when(usageService.findByBucketAndMonth(5L, YearMonth.of(2026, 8))).thenReturn(of(row));

        Assertions.assertEquals(37L, guard.usage("mail", 5L, YearMonth.of(2026, 8)));
        Assertions.assertEquals(4L, guard.usage("sms", 5L, YearMonth.of(2026, 8)));
    }

    @Test
    void bucketWithNoLedgerRowReadsZero() {
        when(usageService.findByBucketAndMonth(5L, YearMonth.of(2026, 8)))
                .thenReturn(java.util.Optional.empty());
        Assertions.assertEquals(0L, guard.usage("mail", 5L, YearMonth.of(2026, 8)));
        Assertions.assertEquals(0L, guard.usedToday("mail", 5L));
    }
}
