package io.softa.starter.message.shared.retry;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.starter.message.shared.ErrorCategory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the (sole) retry strategy: the exponential back-off curve, the
 * {@code MAX_ATTEMPTS} cap, the QUOTA floor, and the category → decision mapping.
 */
class ExponentialBackoffPolicyTest {

    private ExponentialBackoffPolicy policy;

    @BeforeEach
    void setUp() {
        // No jitter, so every delay is exact.
        policy = new ExponentialBackoffPolicy(0.0);
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
    void theWholeScheduleBeforeTheDeadLetter() {
        long[] expected = {30, 60, 120, 240, 480};
        for (int attempt = 0; attempt < expected.length; attempt++) {
            RetryDecision decision = policy.decide(attempt, ErrorCategory.TRANSIENT);
            assertEquals(Duration.ofSeconds(expected[attempt]), ((RetryDecision.Retry) decision).delay(),
                    "attempt " + attempt);
        }
        assertInstanceOf(RetryDecision.DeadLetter.class,
                policy.decide(expected.length, ErrorCategory.TRANSIENT));
    }

    @Test
    void jitterStaysWithinHalfTheDelay() {
        ExponentialBackoffPolicy jittered = new ExponentialBackoffPolicy();
        for (int i = 0; i < 50; i++) {
            long seconds = ((RetryDecision.Retry) jittered.decide(2, ErrorCategory.TRANSIENT)).delay().getSeconds();
            // 120s ± 50%
            assertTrue(seconds >= 60 && seconds <= 180, "delay " + seconds + "s outside 120s ± 50%");
        }
    }

    @Test
    void quotaErrorClampedToFloor() {
        // base would be 30s at attempt 0, but the QUOTA floor is 300s.
        RetryDecision decision = policy.decide(0, ErrorCategory.QUOTA);
        assertInstanceOf(RetryDecision.Retry.class, decision);
        assertTrue(((RetryDecision.Retry) decision).delay().getSeconds() >= 300);
    }

    @Test
    void exhaustedAttemptsDeadLetter() {
        // attempt == MAX_ATTEMPTS (5) → retries exhausted.
        RetryDecision decision = policy.decide(ExponentialBackoffPolicy.MAX_ATTEMPTS, ErrorCategory.TRANSIENT);
        assertInstanceOf(RetryDecision.DeadLetter.class, decision);
    }

    @Test
    void permanentErrorFails() {
        RetryDecision decision = policy.decide(0, ErrorCategory.PERMANENT);
        assertInstanceOf(RetryDecision.Fail.class, decision);
    }
}
