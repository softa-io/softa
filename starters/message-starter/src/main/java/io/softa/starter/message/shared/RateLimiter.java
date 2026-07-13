package io.softa.starter.message.shared;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.service.CacheService;

/**
 * Per-tenant + per-config rate limiter backed by Redis.
 * <p>
 * Two windows apply in tandem:
 * <ul>
 *   <li><b>daily</b> — cumulative sends per tenant + config per day</li>
 *   <li><b>per-minute</b> — sends per tenant + config per minute (smoothing burst)</li>
 * </ul>
 * Either {@code dailyLimit} or {@code minuteLimit} can be {@code null} to
 * disable that window. Counters are atomic on Redis, so multiple app instances
 * share one budget.
 * <p>
 * Semantics: {@code tryAcquire} atomically consumes one token only when both
 * windows still have headroom, returns the exceeded window otherwise. The
 * caller decides what to do (fail synchronously, mark the record QUOTA-failed
 * and retry later, etc.).
 */
@Slf4j
@Component
public class RateLimiter {

    private static final DateTimeFormatter MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final long LUA_ALLOWED = 0L;
    private static final long LUA_DAILY_EXCEEDED = 1L;
    private static final long LUA_MINUTE_EXCEEDED = 2L;
    private static final DefaultRedisScript<Long> TRY_ACQUIRE_SCRIPT =
            new DefaultRedisScript<>("""
                    local dailyLimit = tonumber(ARGV[1])
                    local dailyTtl = tonumber(ARGV[2])
                    local minuteLimit = tonumber(ARGV[3])
                    local minuteTtl = tonumber(ARGV[4])

                    if dailyLimit > 0 then
                      local dailyUsed = tonumber(redis.call('GET', KEYS[1]) or '0')
                      if dailyUsed >= dailyLimit then
                        return 1
                      end
                    end

                    if minuteLimit > 0 then
                      local minuteUsed = tonumber(redis.call('GET', KEYS[2]) or '0')
                      if minuteUsed >= minuteLimit then
                        return 2
                      end
                    end

                    if dailyLimit > 0 then
                      local dailyNew = redis.call('INCR', KEYS[1])
                      if dailyNew == 1 then
                        redis.call('EXPIRE', KEYS[1], dailyTtl)
                      end
                    end

                    if minuteLimit > 0 then
                      local minuteNew = redis.call('INCR', KEYS[2])
                      if minuteNew == 1 then
                        redis.call('EXPIRE', KEYS[2], minuteTtl)
                      end
                    end

                    return 0
                    """, Long.class);

    @Autowired
    private CacheService cacheService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * Try to consume one send-quota token.
     *
     * @param channel       {@code mail} / {@code sms} — scopes the key so Mail and SMS quotas don't collide
     * @param configId      provider / server config id — quotas are per-config
     * @param dailyLimit    max sends per day; {@code null} to disable
     * @param minuteLimit   max sends per minute; {@code null} to disable
     * @return              {@link Outcome} indicating success or which window was exceeded
     */
    public Outcome tryAcquire(String channel, Long configId, Integer dailyLimit, Integer minuteLimit) {
        if ((dailyLimit == null || dailyLimit <= 0)
                && (minuteLimit == null || minuteLimit <= 0)) {
            return Outcome.ALLOWED;
        }
        long tenantId = currentTenantId();
        String id = String.valueOf(configId != null ? configId : 0L);

        String dailyKey = "rl:" + channel + ":daily:" + tenantId + ":" + id
                + ":" + LocalDate.now();
        String minuteKey = "rl:" + channel + ":min:" + tenantId + ":" + id
                + ":" + LocalDateTime.now().format(MINUTE_FMT);
        Long result = stringRedisTemplate.execute(
                TRY_ACQUIRE_SCRIPT,
                List.of(cacheService.getKeyPath(dailyKey), cacheService.getKeyPath(minuteKey)),
                String.valueOf(limit(dailyLimit)),
                "86400",
                String.valueOf(limit(minuteLimit)),
                "120");
        long outcome = result != null ? result : LUA_ALLOWED;
        if (outcome == LUA_DAILY_EXCEEDED) {
            log.warn("RateLimiter: daily quota exceeded channel={} configId={} tenant={} limit={}",
                    channel, configId, tenantId, dailyLimit);
            return Outcome.DAILY_EXCEEDED;
        }
        if (outcome == LUA_MINUTE_EXCEEDED) {
            log.warn("RateLimiter: per-minute quota exceeded channel={} configId={} tenant={} limit={}",
                    channel, configId, tenantId, minuteLimit);
            return Outcome.MINUTE_EXCEEDED;
        }
        return Outcome.ALLOWED;
    }

    private static int limit(Integer n) {
        return n != null ? n : 0;
    }

    private static long currentTenantId() {
        Context ctx = ContextHolder.getContext();
        return ctx != null && ctx.getTenantId() != null ? ctx.getTenantId() : 0L;
    }

    public enum Outcome {
        ALLOWED,
        DAILY_EXCEEDED,
        MINUTE_EXCEEDED;

        public boolean ok() { return this == ALLOWED; }
    }
}
