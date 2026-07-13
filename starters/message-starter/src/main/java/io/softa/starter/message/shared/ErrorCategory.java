package io.softa.starter.message.shared;

import lombok.Getter;

/**
 * Semantic classification of a provider error.
 * <p>
 * Mapped from heterogeneous provider-native codes (SMTP reply codes, Twilio
 * error numbers, Aliyun bizCodes, etc.) so the retry policy has one contract
 * to reason about. See {@link ErrorClassifier}.
 */
@Getter
public enum ErrorCategory {
    /** Network hiccup, timeout, 5xx — should be retried. */
    TRANSIENT(true),
    /** Provider rejected the message outright (bad recipient, bad sender, 550). */
    PERMANENT(false),
    /** Caller sent something invalid (empty body, no recipients, malformed address). */
    INVALID_INPUT(false),
    /** Provider auth failed (bad API key, expired token). Usually needs ops, not retry. */
    AUTH(false),
    /** Daily / per-minute quota exhausted; retry later with extended back-off. */
    QUOTA(true),
    /** Anything we couldn't classify — retry conservatively. */
    UNKNOWN(true);

    private final boolean retryable;

    ErrorCategory(boolean retryable) {
        this.retryable = retryable;
    }
}
