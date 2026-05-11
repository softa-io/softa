package io.softa.framework.orm.sequence.exception;

import io.softa.framework.base.enums.ResponseCode;

/**
 * Row lock on {@code sys_sequence} could not be acquired within
 * {@code innodb_lock_wait_timeout}. Indicates extreme contention on a
 * single (tenant, code) pair; consider switching that code to ALLOW_GAP
 * mode if continuity is not required.
 */
public class SequenceTimeoutException extends SequenceException {

    public SequenceTimeoutException(String code, Throwable cause) {
        super(code, ResponseCode.ERROR, "Sequence allocation timed out (row lock wait): " + code, cause);
    }
}
