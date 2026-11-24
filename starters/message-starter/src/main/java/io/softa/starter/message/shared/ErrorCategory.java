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
    /**
     * The provider was not reached or answered that it is busy (a refused connection, 503) — should be
     * retried. For SMS only a failure certain not to have sent anything is TRANSIENT; see
     * {@link #OUTCOME_UNKNOWN}.
     */
    TRANSIENT(true),
    /**
     * The request may have reached the provider, which may have sent it: no answer came back (a read
     * timeout, a connection reset), the answer could not be read, or the provider failed while handling
     * it (500 / 502 / 504). Sending again could deliver the message twice, so the record fails instead;
     * whoever looks at it checks the provider's console and Retries it by hand when it did not go out.
     */
    OUTCOME_UNKNOWN(false),
    /** Provider rejected the message outright (bad recipient, bad sender, 550). */
    PERMANENT(false),
    /** Caller sent something invalid (empty body, no recipients, malformed address). */
    INVALID_INPUT(false),
    /** Provider auth failed (bad API key, expired token). Usually needs ops, not retry. */
    AUTH(false),
    /**
     * The deployment cannot send this message as configured: no server or provider, a missing or
     * broken template, the monthly quota used up. Never heals by retrying — fix the configuration,
     * then Retry the record.
     */
    CONFIG(false),
    /** Daily / per-minute quota exhausted; retry later with extended back-off. */
    QUOTA(true),
    /** Anything we couldn't classify — retry conservatively. */
    UNKNOWN(true);

    private final boolean retryable;

    ErrorCategory(boolean retryable) {
        this.retryable = retryable;
    }
}
