package io.softa.framework.orm.service;

import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * Marks the writes of a file import, for messages that should speak to the person who uploaded the
 * file rather than to someone editing a form: a refusal reads "update" for an import row and "edit"
 * for a form. Nothing is decided by it — the same checks run either way.
 */
public final class ImportScope {

    private static final ScopedValue<Boolean> ACTIVE = ScopedValue.newInstance();

    private ImportScope() {
    }

    /** Whether the current write belongs to a file import. */
    public static boolean isActive() {
        try {
            return Boolean.TRUE.equals(ACTIVE.get());
        } catch (NoSuchElementException e) {
            return false;
        }
    }

    /** Run the action as a file import's writes. */
    public static void run(Runnable action) {
        ScopedValue.where(ACTIVE, Boolean.TRUE).run(action);
    }

    /** Run the action as a file import's writes and return its result. */
    public static <T> T call(Supplier<T> action) {
        return ScopedValue.where(ACTIVE, Boolean.TRUE).call(action::get);
    }
}
