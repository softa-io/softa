package io.softa.starter.message.shared.retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.starter.message.shared.ErrorCategory;

/**
 * Exponential back-off with configurable base, cap, multiplier and jitter.
 * Delay sequence (default base=30s, multiplier=2, cap=3600s):
 * <pre>
 * attempt 0 → 30s
 * attempt 1 → 60s
 * attempt 2 → 120s
 * attempt 3 → 240s
 * …
 * cap       → 3600s
 * </pre>
 * Jitter is additive in the band {@code [-jitter * delay, +jitter * delay]}
 * (default ±50%) — cheap protection against thundering herd when a provider
 * comes back online and everyone retries at once.
 * <p>
 * {@link ErrorCategory#QUOTA} clamps the delay to at least
 * {@link RetryProperties.Exponential} so we don't
 * immediately slam a rate-limited provider with a 30s retry.
 */
@Component
public class ExponentialBackoffPolicy {

    @Autowired
    private RetryProperties properties;

    public RetryDecision decide(int attempt, ErrorCategory category) {
        if (!category.isRetryable()) {
            return isPermanent(category) ? new RetryDecision.Fail() : new RetryDecision.DeadLetter();
        }
        int max = properties.getDefaultMaxAttempts();
        if (attempt >= max) {
            return new RetryDecision.DeadLetter();
        }
        return new RetryDecision.Retry(computeDelay(attempt, category));
    }

    private Duration computeDelay(int attempt, ErrorCategory category) {
        RetryProperties.Exponential cfg = properties.getExponential();
        double base = cfg.getBaseSeconds();
        double mult = cfg.getMultiplier();
        double cap = cfg.getMaxSeconds();
        // Math.pow grows fast enough that attempt > 30 would overflow; clamp first.
        int safeAttempt = Math.min(attempt, 30);
        double raw = base * Math.pow(mult, safeAttempt);
        double bounded = Math.min(raw, cap);

        double jitterFactor = cfg.getJitter();
        if (jitterFactor > 0) {
            double rand = ThreadLocalRandom.current().nextDouble(-jitterFactor, jitterFactor);
            bounded = Math.max(1.0, bounded * (1.0 + rand));
        }

        if (category == ErrorCategory.QUOTA) {
            bounded = Math.max(bounded, cfg.getQuotaFloorSeconds());
        }
        return Duration.ofSeconds((long) bounded);
    }

    private static boolean isPermanent(ErrorCategory c) {
        return c == ErrorCategory.PERMANENT || c == ErrorCategory.INVALID_INPUT || c == ErrorCategory.AUTH;
    }
}
