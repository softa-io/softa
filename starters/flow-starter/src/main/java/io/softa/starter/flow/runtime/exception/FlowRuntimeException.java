package io.softa.starter.flow.runtime.exception;

/**
 * Base runtime exception for all flow execution errors.
 */
public class FlowRuntimeException extends RuntimeException {

    public FlowRuntimeException(String message) {
        super(message);
    }

    public FlowRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}

