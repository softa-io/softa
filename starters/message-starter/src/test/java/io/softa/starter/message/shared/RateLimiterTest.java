package io.softa.starter.message.shared;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.service.CacheService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimiterTest {

    private RateLimiter limiter;
    private CacheService cacheService;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiter();
        cacheService = mock(CacheService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        when(cacheService.getKeyPath(anyString())).thenAnswer(inv -> "softa:" + inv.getArgument(0));
        ReflectionTestUtils.setField(limiter, "cacheService", cacheService);
        ReflectionTestUtils.setField(limiter, "stringRedisTemplate", redisTemplate);
    }

    @Test
    void disabledLimitsDoNotTouchRedis() {
        RateLimiter.Outcome outcome = limiter.tryAcquire("sms", 1L, null, 0);

        assertEquals(RateLimiter.Outcome.ALLOWED, outcome);
        verifyNoInteractions(cacheService, redisTemplate);
    }

    @Test
    void luaAllowedMapsToAllowed() {
        redisResult(0L);

        RateLimiter.Outcome outcome = limiter.tryAcquire("sms", 1L, 100, 10);

        assertEquals(RateLimiter.Outcome.ALLOWED, outcome);
    }

    @Test
    void luaDailyExceededMapsToDailyOutcome() {
        redisResult(1L);

        RateLimiter.Outcome outcome = limiter.tryAcquire("sms", 1L, 100, 10);

        assertEquals(RateLimiter.Outcome.DAILY_EXCEEDED, outcome);
    }

    @Test
    void luaMinuteExceededMapsToMinuteOutcome() {
        redisResult(2L);

        RateLimiter.Outcome outcome = limiter.tryAcquire("sms", 1L, 100, 10);

        assertEquals(RateLimiter.Outcome.MINUTE_EXCEEDED, outcome);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void theWindowsAreTheServersSharedByEveryTenantItCarries() {
        // A platform server relaying for many tenants stops at its own daily limit, not at that limit
        // once per tenant: two tenants' sends count in the same two windows.
        redisResult(0L);
        for (long tenant : new long[] {5L, 7L}) {
            Context ctx = new Context();
            ctx.setTenantId(tenant);
            ContextHolder.runWith(ctx, () -> limiter.tryAcquire("mail", 99L, 100, 10));
        }

        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(2)).execute(any(DefaultRedisScript.class), keys.capture(),
                any(), any(), any(), any());
        for (List<String> pair : (List<List<String>>) (List) keys.getAllValues()) {
            assertTrue(pair.get(0).startsWith("softa:rl:mail:daily:99:"), pair.get(0));
            assertTrue(pair.get(1).startsWith("softa:rl:mail:min:99:"), pair.get(1));
        }
        assertEquals(keys.getAllValues().get(0).get(0), keys.getAllValues().get(1).get(0));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void redisResult(Long result) {
        doReturn(result).when(redisTemplate).execute(
                any(DefaultRedisScript.class),
                any(List.class),
                any(), any(), any(), any());
    }
}
