package io.softa.framework.orm.sequence.exception;

import io.softa.framework.base.enums.ResponseCode;

/**
 * {@code next()} / {@code nextBatch()} / {@code peek()} was called under a
 * cross-tenant context ({@code Context.isCrossTenant() == true}). Sequence
 * allocation must be bound to a concrete tenant; cross-tenant
 * platform-management code paths must not consume per-tenant counters.
 */
public class SequenceCrossTenantException extends SequenceException {

    public SequenceCrossTenantException(String code) {
        super(code, ResponseCode.PERMISSION_DENIED,
                "Sequence allocation is not allowed under cross-tenant context: " + code);
    }
}
