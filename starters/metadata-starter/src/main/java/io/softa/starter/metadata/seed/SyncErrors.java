package io.softa.starter.metadata.seed;

/** How a seed sync reports what went wrong. */
public final class SyncErrors {

    private SyncErrors() {
    }

    /** The message of an exception's root cause — the database's own words, not its wrappers'. */
    public static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
