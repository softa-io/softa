package io.softa.framework.base.exception;

import io.softa.framework.base.enums.ResponseCode;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Permission exception
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PermissionException extends BaseException {

    private ResponseCode responseCode = ResponseCode.PERMISSION_DENIED;

    /**
     * Accepts variable arguments, optionally ending with a Throwable for enhanced error tracking.
     */
    public PermissionException(String message, Object... args) {
        super(message, args);
    }
}
