package io.softa.framework.base.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.enums.ResponseCode;

/**
 * Data consistency exception, such as version conflict.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class VersionException extends BaseException {

    private ResponseCode responseCode = ResponseCode.VERSION_CHANGED;

    /**
     * Accepts variable arguments, optionally ending with a Throwable for enhanced error tracking.
     */
    public VersionException(String message, Object... args) {
        super(message, args);
    }
}
