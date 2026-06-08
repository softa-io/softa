package io.softa.framework.base.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.enums.ResponseCode;

/**
 * User not found exception.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserNotFoundException extends BaseException {

    private ResponseCode responseCode = ResponseCode.USER_NOT_FOUND;

    /**
     * Accepts variable arguments, optionally ending with a Throwable for enhanced error tracking.
     */
    public UserNotFoundException(String message, Object... args) {
        super(message, args);
    }
}
