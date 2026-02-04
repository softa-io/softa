package io.softa.framework.base.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.enums.ResponseCode;

/**
 * External system exception
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExternalException extends BaseException {

    private ResponseCode responseCode = ResponseCode.EXTERNAL_EXCEPTION;

    /**
     * Accepts variable arguments, optionally ending with a Throwable for enhanced error tracking.
     */
    public ExternalException(String message, Object... args) {
        super(message, args);
    }

    /**
     * Accepts variable arguments, optionally ending with a Throwable for enhanced error tracking.
     */
    public ExternalException(ResponseCode responseCode, String message, Object... args) {
        super(message, args);
        this.responseCode = responseCode;
    }
}
