package io.softa.framework.orm.utils;

import java.math.BigInteger;

import io.softa.framework.base.exception.IllegalArgumentException;

/**
 * Numeric helpers for optimistic-lock version fields.
 */
public final class VersionLockUtils {

    private VersionLockUtils() {
    }

    public static Object increment(Object value) {
        return switch (value) {
            case Integer i -> i + 1;
            case Long l -> l + 1L;
            case Short s -> (short) (s + 1);
            case Byte b -> (byte) (b + 1);
            case BigInteger bi -> bi.add(BigInteger.ONE);
            case null -> throw unsupported(null);
            default -> throw unsupported(value);
        };
    }

    public static Object decrement(Object value) {
        return switch (value) {
            case Integer i -> i - 1;
            case Long l -> l - 1L;
            case Short s -> (short) (s - 1);
            case Byte b -> (byte) (b - 1);
            case BigInteger bi -> bi.subtract(BigInteger.ONE);
            case null -> throw unsupported(null);
            default -> throw unsupported(value);
        };
    }

    private static IllegalArgumentException unsupported(Object value) {
        String type = value == null ? "null" : value.getClass().getName();
        return new IllegalArgumentException("Optimistic-lock version must be an integer number type, but was {0}.", type);
    }
}
