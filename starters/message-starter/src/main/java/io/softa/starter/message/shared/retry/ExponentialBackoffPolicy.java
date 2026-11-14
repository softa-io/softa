package io.softa.starter.message.shared.retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

import io.softa.starter.message.shared.ErrorCategory;

/**
 * Exponential back-off: a send that fails transiently is retried after 30s, 60s, 120s, 240s and
 * 480s — each delay doubled — and dead-lettered when it fails again after its {@link #MAX_ATTEMPTS}
 * retries, about a quarter of an hour after the first failure:
 * <pre>
 * attempt 0 → 30s
 * attempt 1 → 60s
 * attempt 2 → 120s
 * attempt 3 → 240s
 * attempt 4 → 480s
 * attempt 5 → dead letter
 * </pre>
 * Jitter is additive in the band {@code [-50%, +50%]} of the delay — cheap protection against a
 * thundering herd when a provider comes back online and everyone retries at once.
 * <p>
 * {@link ErrorCategory#QUOTA} waits at least {@link #QUOTA_FLOOR_SECONDS}, so a rate-limited
 * provider is not hit again 30 seconds later. The numbers are constants: no deployment has needed
 * other values, and changing them means understanding how they interact.
 */
@Component
public class ExponentialBackoffPolicy {

    /** Retries a send gets before it is dead-lettered; revivals by the zombie sweep count too. */
    public static final int MAX_ATTEMPTS = 5;

    static final int BASE_SECONDS = 30;

    static final double MULTIPLIER = 2.0;

    static final double JITTER = 0.5;

    /** A rate-limited (QUOTA) send waits at least this long before its next attempt. */
    static final int QUOTA_FLOOR_SECONDS = 300;

    private final double jitter;

    public ExponentialBackoffPolicy() {
        this(JITTER);
    }

    /** For tests: {@code jitter = 0} makes every delay exact. */
    ExponentialBackoffPolicy(double jitter) {
        this.jitter = jitter;
    }

    public RetryDecision decide(int attempt, ErrorCategory category) {
        if (!category.isRetryable()) {
            return isPermanent(category) ? new RetryDecision.Fail() : new RetryDecision.DeadLetter();
        }
        if (attempt >= MAX_ATTEMPTS) {
            return new RetryDecision.DeadLetter();
        }
        return new RetryDecision.Retry(computeDelay(attempt, category));
    }

    private Duration computeDelay(int attempt, ErrorCategory category) {
        // attempt < MAX_ATTEMPTS here, so the longest delay is 30s * 2^4 = 480s.
        double bounded = BASE_SECONDS * Math.pow(MULTIPLIER, attempt);

        if (jitter > 0) {
            double rand = ThreadLocalRandom.current().nextDouble(-jitter, jitter);
            bounded = Math.max(1.0, bounded * (1.0 + rand));
        }

        if (category == ErrorCategory.QUOTA) {
            bounded = Math.max(bounded, QUOTA_FLOOR_SECONDS);
        }
        return Duration.ofSeconds((long) bounded);
    }

    private static boolean isPermanent(ErrorCategory c) {
        return c == ErrorCategory.PERMANENT || c == ErrorCategory.INVALID_INPUT || c == ErrorCategory.AUTH
                || c == ErrorCategory.CONFIG;
    }
}
