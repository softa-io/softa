package io.softa.starter.flow.runtime.exception;

/**
 * Thrown when a caller is not authorized to view or act on a flow resource —
 * e.g. requesting another user's approval tasks or an instance's approval
 * history without being a participant or the initiator.
 * <p>
 * Mapped to HTTP 403 ({@code PERMISSION_DENIED}) by {@code FlowExceptionHandler}.
 */
public class FlowAuthorizationException extends RuntimeException {

    public FlowAuthorizationException(String message) {
        super(message);
    }
}
