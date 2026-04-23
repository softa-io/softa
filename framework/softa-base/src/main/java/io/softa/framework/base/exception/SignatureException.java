package io.softa.framework.base.exception;

/**
 * Raised when signing or verification cannot complete — missing keys, malformed
 * headers, expired timestamps, replayed nonces, cryptographic mismatch, etc.
 */
public class SignatureException extends RuntimeException {

    public SignatureException(String message) {
        super(message);
    }

    public SignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
