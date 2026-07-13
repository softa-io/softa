package io.softa.starter.message.shared.retry;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.message.shared.ErrorCategory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the (sole) retry strategy: exponential back-off driven by
 * {@code softa.message.retry.exponential.*}, the {@code defaultMaxAttempts}
 * cap, the QUOTA floor, and the category → decision mapping.
 */
class ExponentialBackoffPolicyTest {

    private ExponentialBackoffPolicy policy;
    private RetryProperties properties;

    @BeforeEach
    void setUp() {
        policy = new ExponentialBackoffPolicy();
        properties = new RetryProperties();
        // Disable jitter so delay computation is deterministic in this test.
        properties.getExponential().setJitter(0.0);
        properties.getExponential().setBaseSeconds(30);
        properties.getExponential().setMultiplier(2.0);
        properties.getExponential().setMaxSeconds(3600);
        properties.setDefaultMaxAttempts(5);
        ReflectionTestUtils.setField(policy, "properties", properties);
    }

    @Test
    void firstRetryUsesGlobalBaseDelay() {
        // attempt 0 → 30 * 2^0 = 30s
        RetryDecision decision = policy.decide(0, ErrorCategory.TRANSIENT);
        assertInstanceOf(RetryDecision.Retry.class, decision);
        assertEquals(Duration.ofSeconds(30), ((RetryDecision.Retry) decision).delay());
    }

    @Test
    void delayCompoundsWithMultiplier() {
        // attempt 2 → 30 * 2^2 = 120s
        RetryDecision decision = policy.decide(2, ErrorCategory.TRANSIENT);
        assertInstanceOf(RetryDecision.Retry.class, decision);
        assertEquals(Duration.ofSeconds(120), ((RetryDecision.Retry) decision).delay());
    }

    @Test
    void delayCappedByMaxSeconds() {
        // 30 * 2^4 = 480s, which exceeds the lowered cap of 100s.
        properties.getExponential().setMaxSeconds(100);
        RetryDecision decision = policy.decide(4, ErrorCategory.TRANSIENT);
        assertInstanceOf(RetryDecision.Retry.class, decision);
        assertTrue(((RetryDecision.Retry) decision).delay().getSeconds() <= 100,
                "delay should be capped at maxSeconds=100");
    }

    @Test
    void quotaErrorClampedToFloor() {
        // base would be 30s at attempt 0, but the QUOTA floor is 300s.
        properties.getExponential().setQuotaFloorSeconds(300);
        RetryDecision decision = policy.decide(0, ErrorCategory.QUOTA);
        assertInstanceOf(RetryDecision.Retry.class, decision);
        assertTrue(((RetryDecision.Retry) decision).delay().getSeconds() >= 300);
    }

    @Test
    void exhaustedAttemptsDeadLetter() {
        // attempt == defaultMaxAttempts (5) → retries exhausted.
        RetryDecision decision = policy.decide(5, ErrorCategory.TRANSIENT);
        assertInstanceOf(RetryDecision.DeadLetter.class, decision);
    }

    @Test
    void permanentErrorFails() {
        RetryDecision decision = policy.decide(0, ErrorCategory.PERMANENT);
        assertInstanceOf(RetryDecision.Fail.class, decision);
    }
}
