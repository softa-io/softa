package io.softa.framework.orm.sequence.exception;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.exception.BaseException;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Base type for sequence allocation failures. Subclasses pick a more
 * specific {@link ResponseCode} via the constructor so the standard
 * {@link io.softa.framework.web.handler.WebExceptionHandler} maps each
 * variant to a meaningful HTTP-side error.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
public class SequenceException extends BaseException {

    private final String sequenceCode;

    public SequenceException(String sequenceCode, String message) {
        super(message);
        this.sequenceCode = sequenceCode;
    }

    public SequenceException(String sequenceCode, ResponseCode responseCode, String message) {
        super(message);
        this.sequenceCode = sequenceCode;
        setResponseCode(responseCode);
    }

    public SequenceException(String sequenceCode, ResponseCode responseCode, String message, Throwable cause) {
        super(message);
        this.sequenceCode = sequenceCode;
        setResponseCode(responseCode);
        if (cause != null) {
            initCause(cause);
        }
    }
}
