package io.softa.starter.message.push;

/**
 * How a send ended. A push is never tried again: whatever did not go, the inbox message still holds, and a blocked
 * network or a wrong key would fail the same way on every try.
 *
 * @param outcome the outcome
 * @param reason  what went wrong, for the log: the service's status and error code, or the exception's type and
 *                message; null on success
 */
public record PushResult(Outcome outcome, String reason) {

    public enum Outcome {
        /** The service accepted it. */
        SENT,
        /** The token is no longer valid: stop sending to it. */
        TOKEN_INVALID,
        /** The service could not be reached, was in trouble, or limited this device's rate. */
        FAILED,
        /** The credentials or the target are misconfigured. The token says nothing. */
        CONFIG_ERROR,
        /** The service refused this notification; the token may be fine. */
        REJECTED
    }

    static PushResult sent() {
        return new PushResult(Outcome.SENT, null);
    }

    static PushResult tokenInvalid(String reason) {
        return new PushResult(Outcome.TOKEN_INVALID, reason);
    }

    static PushResult failed(String reason) {
        return new PushResult(Outcome.FAILED, reason);
    }

    static PushResult configError(String reason) {
        return new PushResult(Outcome.CONFIG_ERROR, reason);
    }

    static PushResult rejected(String reason) {
        return new PushResult(Outcome.REJECTED, reason);
    }
}
