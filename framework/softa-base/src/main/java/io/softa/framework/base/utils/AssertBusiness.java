package io.softa.framework.base.utils;

import io.softa.framework.base.exception.BusinessException;

public abstract class AssertBusiness extends Assert {

    /** Throw BusinessException. */
    private static void throwException(String message, Object... args) {
        throw new BusinessException(message, args);
    }
}
