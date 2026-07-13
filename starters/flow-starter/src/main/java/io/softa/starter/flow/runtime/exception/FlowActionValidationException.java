package io.softa.starter.flow.runtime.exception;

/**
 * Raised when a flow action request fails validation (bad input, invalid state, actor not eligible, etc.).
 */
public class FlowActionValidationException extends FlowRuntimeException {

    public FlowActionValidationException(String message) {
        super(message);
    }
}

