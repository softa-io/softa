package io.softa.starter.message.shared;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.service.CacheService;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void redisResult(Long result) {
        doReturn(result).when(redisTemplate).execute(
                any(DefaultRedisScript.class),
                any(List.class),
                any(), any(), any(), any());
    }
}
