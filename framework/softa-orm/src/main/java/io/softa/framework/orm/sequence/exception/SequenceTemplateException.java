package io.softa.framework.orm.sequence.exception;

import io.softa.framework.base.enums.ResponseCode;

/**
 * Sequence template is malformed or contains unknown tokens.
 * Surfaces both at config save time (validation) and rendering time
 * (defensive).
 */
public class SequenceTemplateException extends SequenceException {

    public SequenceTemplateException(String code, String message) {
        super(code, ResponseCode.BAD_REQUEST, message);
    }

    public SequenceTemplateException(String code, String message, Throwable cause) {
        super(code, ResponseCode.BAD_REQUEST, message, cause);
    }
}
