package io.softa.starter.message.shared.retry;

import java.time.Duration;

/**
 * Outcome of {@link ExponentialBackoffPolicy#decide}.
 * <p>
 * Using a sealed record hierarchy keeps call sites honest — the three terminal
 * dispositions (retry / give-up-to-fail / give-up-to-dead-letter) can't be
 * conflated by accident.
 */
public sealed interface RetryDecision {

    /** Schedule another attempt after {@link #delay}. */
    record Retry(Duration delay) implements RetryDecision {}

    /** Stop — the error is permanent. Caller flips the record to {@code FAILED}. */
    record Fail() implements RetryDecision {}

    /** Stop — retries exhausted. Caller flips the record to {@code DEAD_LETTER}. */
    record DeadLetter() implements RetryDecision {}
}
