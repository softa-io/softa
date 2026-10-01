package io.softa.starter.metadata.seed;

import java.util.function.Supplier;

import io.softa.framework.base.context.ContextUtils;

/** Helpers the seed sync classes share. */
final class SyncSupport {

    static final int ERROR_LIMIT = 4000;

    private SyncSupport() {
    }

    /** The sync's own bookkeeping, which no tenant or role scope applies to. */
    static <T> T asSystem(Supplier<T> action) {
        return ContextUtils.inSystemContext(action);
    }

    static void asSystem(Runnable action) {
        ContextUtils.inSystemContext(action);
    }

    /** The message of an exception, with its root cause's when that says something else. */
    static String messageOf(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return root == e ? message : e.getMessage() + " (" + message + ")";
    }

    static String truncate(String text) {
        return text == null || text.length() <= ERROR_LIMIT ? text : text.substring(0, ERROR_LIMIT);
    }
}
