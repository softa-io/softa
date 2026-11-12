package io.softa.starter.message.shared;

/**
 * Why a send record cannot go out as configured, found while preparing it: an error code
 * {@link ErrorClassifier} knows, and the reason that goes onto the record. Thrown and caught inside
 * the delivery processors only; it never reaches a caller.
 */
public final class SendRejection extends RuntimeException {

    private final String code;

    public SendRejection(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
