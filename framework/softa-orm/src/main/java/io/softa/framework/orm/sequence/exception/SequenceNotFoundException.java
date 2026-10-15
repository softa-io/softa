package io.softa.framework.orm.sequence.exception;

import io.softa.framework.base.enums.ResponseCode;

/**
 * No {@code sys_sequence} row exists for the current tenant + code.
 * Most likely cause: the tenant's seed files do not carry the sequence — it
 * reaches a tenant when the tenant is set up, or through a seed sync once a
 * release adds it — or the code is misspelled.
 */
public class SequenceNotFoundException extends SequenceException {

    public SequenceNotFoundException(String code) {
        super(code, ResponseCode.REQUEST_NOT_FOUND, "Sequence configuration not found: " + code);
    }
}
