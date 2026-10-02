package io.softa.framework.base.utils;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.exception.IllegalArgumentException;

/**
 * Parameterized Assert, suitable for IllegalArgumentException message templates.
 */
public abstract class Assert {

    /** Throw IllegalArgumentException. */
    private static void throwException(String message, Object... args) {
        throw new IllegalArgumentException(message, args);
    }

    public static void isTrue(Boolean object, String message, Object... args) {
        if (!Boolean.TRUE.equals(object)) {
            throwException(message, args);
        }
    }

    public static void notTrue(Boolean object, String message, Object... args) {
        if (Boolean.TRUE.equals(object)) {
            throwException(message, args);
        }
    }

    public static void isEmpty(@Nullable Collection<?> objects, String message, Object... args) {
        if (!CollectionUtils.isEmpty(objects)) {
            throwException(message, args);
        }
    }

    /** Collection is not empty. */
    public static void notEmpty(@Nullable Collection<?> objects, String message, Object... args) {
        if (CollectionUtils.isEmpty(objects)) {
            throwException(message, args);
        }
    }

    /** Map is not empty. */
    public static void notEmpty(@Nullable Map<?, ?> objects, String message, Object... args) {
        if (CollectionUtils.isEmpty(objects)) {
            throwException(message, args);
        }
    }

    public static void notBlank(@Nullable String s, String message, Object... args) {
        if (StringUtils.isBlank(s)) {
            throwException(message, args);
        }
    }

    /** Collection does not contain null or blank string. */
    public static void allNotBlank(@Nullable Collection<String> objects, String message, Object... args) {
        if (objects == null || objects.isEmpty() || objects.stream().anyMatch(StringUtils::isBlank)) {
            throwException(message, args);
        }
    }

    public static void isEqual(@Nullable Object object1, @Nullable Object object2, String message, Object... args) {
        if (!Objects.equals(object1, object2)) {
            throwException(message, args);
        }
    }

    public static void notEqual(@Nullable Object object1, @Nullable Object object2, String message, Object... args) {
        if (Objects.equals(object1, object2)) {
            throwException(message, args);
        }
    }

    public static void notNull(@Nullable Object object, String message, Object... args) {
        if (object == null) {
            throwException(message, args);
        }
    }

    /** Object[] does not contain null. */
    public static void allNotNull(@Nullable Object[] objects, String message, Object... args) {
        if (objects == null || Arrays.stream(objects).anyMatch(Objects::isNull)) {
            throwException(message, args);
        }
    }

    /**
     * Collection does not contain null.
     *
     * <p>Scanned rather than asked with {@code contains(null)}: an immutable collection — anything
     * from {@code List.of} — answers that question by throwing NullPointerException, whatever it
     * actually holds. So the check meant to produce a readable assertion produced an NPE instead,
     * from inside the assertion, for a collection that was perfectly valid.
     *
     * <p>The framework hands itself exactly such a collection. The import pipeline's row-by-row
     * fallback persists {@code List.of(row)}, so every ONLY_CREATE template failed there — and the
     * NPE also masked whatever the batch attempt had failed on, leaving the importer with "an
     * unexpected error" and the real cause nowhere to be found.
     */
    public static void allNotNull(@Nullable Collection<?> objects, String message, Object... args) {
        if (objects == null || objects.isEmpty() || objects.stream().anyMatch(Objects::isNull)) {
            throwException(message, args);
        }
    }

}
