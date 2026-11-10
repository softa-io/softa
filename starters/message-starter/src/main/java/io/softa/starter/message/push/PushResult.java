package io.softa.starter.message.push;

import java.time.Duration;

/**
 * How a send ended.
 *
 * @param outcome    the outcome
 * @param retryAfter how long to wait before trying again, when the service said; otherwise null
 * @param reason     the service's reason, for the log; null on success
 */
public record PushResult(Outcome outcome, Duration retryAfter, String reason) {

    public enum Outcome {
        /** The service accepted it. */
        SENT,
        /** The token is no longer valid: stop sending to it. */
        TOKEN_INVALID,
        /** A temporary failure, of the service or the network: worth trying again. */
        RETRY,
        /** The credentials or the target are misconfigured. The token says nothing, and trying again will not help. */
        CONFIG_ERROR,
        /** The service refused this notification. Trying again will not help, and the token may be fine. */
        REJECTED
    }

    static PushResult sent() {
        return new PushResult(Outcome.SENT, null, null);
    }

    static PushResult tokenInvalid(String reason) {
        return new PushResult(Outcome.TOKEN_INVALID, null, reason);
    }

    static PushResult retry(Duration retryAfter, String reason) {
        return new PushResult(Outcome.RETRY, retryAfter, reason);
    }

    static PushResult configError(String reason) {
        return new PushResult(Outcome.CONFIG_ERROR, null, reason);
    }

    static PushResult rejected(String reason) {
        return new PushResult(Outcome.REJECTED, null, reason);
    }
}
