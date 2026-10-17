package io.softa.framework.orm.aspect;

import java.util.function.Supplier;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;

/**
 * The caller's permission checks, resumed inside a {@code @SkipPermissionCheck} method.
 *
 * <p>The JDBC write methods skip permission checks because what they read for themselves — the stored
 * rows a diff starts from, the related rows a cascaded value is computed from — is bookkeeping of a
 * write already authorized above them. The child rows a nested payload writes are not bookkeeping: they
 * are the caller's own writes to another model, and are judged as the caller — that model's field
 * guards, sensitive fields and row range — through {@link #asCaller}.
 *
 * <p>"The caller" is whoever called the innermost skipping method: a request comes back with its checks
 * on, a system call that was already skipping stays skipped.
 */
public final class CallerPermissions {

    /** Whether the caller of the innermost {@code @SkipPermissionCheck} method was itself skipping. */
    static final ScopedValue<Boolean> CALLER_SKIPPED = ScopedValue.newInstance();

    private CallerPermissions() {}

    /**
     * Run {@code action} with the permission checks of the caller of the innermost
     * {@code @SkipPermissionCheck} method. Outside one, it runs as it is.
     */
    public static <T> T asCaller(Supplier<T> action) {
        if (!CALLER_SKIPPED.isBound() || !ContextHolder.existContext()) {
            return action.get();
        }
        Context context = ContextHolder.getContext();
        boolean previous = context.isSkipPermissionCheck();
        try {
            context.setSkipPermissionCheck(CALLER_SKIPPED.get());
            return action.get();
        } finally {
            context.setSkipPermissionCheck(previous);
        }
    }
}
