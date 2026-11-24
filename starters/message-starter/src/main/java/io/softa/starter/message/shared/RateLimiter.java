package io.softa.starter.message.shared;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.service.CacheService;

/**
 * Per-server / per-provider rate limiter backed by Redis: the limits a send
 * server or SMS provider config declares are that server's, shared by every
 * tenant whose sends it carries — a platform server relaying for many tenants
 * stops at its own daily limit, not at that limit once per tenant. What each
 * tenant may send is the quota's business ({@link SendQuotaGuard}: its monthly allowance and daily cap).
 * <p>
 * Two windows apply in tandem:
 * <ul>
 *   <li><b>daily</b> — cumulative sends per config per day</li>
 *   <li><b>per-minute</b> — sends per config per minute (smoothing burst)</li>
 * </ul>
 * Config ids are distributed ids, unique across the tenant and platform server
 * tables and the provider table, so the channel and the id name one server.
 * Either {@code dailyLimit} or {@code minuteLimit} can be {@code null} to
 * disable that window. Counters are atomic on Redis, so multiple app instances
 * share one budget.
 * <p>
 * Semantics: {@code tryAcquire} atomically consumes one token only when both
 * windows still have headroom, returns the exceeded window otherwise. The two
 * are handled differently by the delivery processors: the day's window lifts
 * tomorrow, so the record fails ({@link ErrorClassifier#SEND_LIMIT_DAILY_EXCEEDED});
 * the minute's lifts within a minute, so the record is put off to
 * {@link #nextMinuteWindow()} without spending a retry
 * ({@link ErrorClassifier#SEND_LIMIT_MINUTE_EXCEEDED}).
 */
@Slf4j
@Component
public class RateLimiter {

    /** The most a send put off by the per-minute window is spread past the window's start. */
    static final int DEFER_SPREAD_SECONDS = 10;

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
     * @param configId      provider / server config id — the windows are the config's, across tenants
     * @param dailyLimit    max sends per day; {@code null} to disable
     * @param minuteLimit   max sends per minute; {@code null} to disable
     * @return              {@link Outcome} indicating success or which window was exceeded
     */
    public Outcome tryAcquire(String channel, Long configId, Integer dailyLimit, Integer minuteLimit) {
        if ((dailyLimit == null || dailyLimit <= 0)
                && (minuteLimit == null || minuteLimit <= 0)) {
            return Outcome.ALLOWED;
        }
        String id = String.valueOf(configId != null ? configId : 0L);

        String dailyKey = "rl:" + channel + ":daily:" + id + ":" + LocalDate.now();
        String minuteKey = "rl:" + channel + ":min:" + id + ":" + LocalDateTime.now().format(MINUTE_FMT);
        Long result = stringRedisTemplate.execute(
                TRY_ACQUIRE_SCRIPT,
                List.of(cacheService.getKeyPath(dailyKey), cacheService.getKeyPath(minuteKey)),
                String.valueOf(limit(dailyLimit)),
                "86400",
                String.valueOf(limit(minuteLimit)),
                "120");
        long outcome = result != null ? result : LUA_ALLOWED;
        if (outcome == LUA_DAILY_EXCEEDED) {
            log.warn("RateLimiter: daily limit reached channel={} configId={} limit={} (send for tenant {})",
                    channel, configId, dailyLimit, currentTenantId());
            return Outcome.DAILY_EXCEEDED;
        }
        if (outcome == LUA_MINUTE_EXCEEDED) {
            log.warn("RateLimiter: per-minute limit reached channel={} configId={} limit={} (send for tenant {})",
                    channel, configId, minuteLimit, currentTenantId());
            return Outcome.MINUTE_EXCEEDED;
        }
        return Outcome.ALLOWED;
    }

    /**
     * When the per-minute window next opens: the start of the next wall-clock minute, the key the
     * window counts under, plus up to {@link #DEFER_SPREAD_SECONDS} seconds so the records put off
     * together do not all come due in the same instant.
     */
    public static LocalDateTime nextMinuteWindow() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
                .plusSeconds(ThreadLocalRandom.current().nextInt(DEFER_SPREAD_SECONDS + 1));
    }

    private static int limit(Integer n) {
        return n != null ? n : 0;
    }

    /** For the log only: the windows do not depend on it. */
    private static Long currentTenantId() {
        Context ctx = ContextHolder.getContext();
        return ctx != null ? ctx.getTenantId() : null;
    }

    public enum Outcome {
        ALLOWED(null),
        DAILY_EXCEEDED(ErrorClassifier.SEND_LIMIT_DAILY_EXCEEDED),
        MINUTE_EXCEEDED(ErrorClassifier.SEND_LIMIT_MINUTE_EXCEEDED);

        /** The error code a send stopped by this window is recorded with. */
        private final String errorCode;

        Outcome(String errorCode) {
            this.errorCode = errorCode;
        }

        public boolean ok() { return this == ALLOWED; }

        public String errorCode() { return errorCode; }
    }
}
