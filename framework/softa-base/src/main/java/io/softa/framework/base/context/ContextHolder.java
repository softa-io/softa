package io.softa.framework.base.context;

import java.util.NoSuchElementException;
import java.util.Objects;

public final class ContextHolder {

    private static final ScopedValue<Context> CONTEXT_VALUE = ScopedValue.newInstance();

    private ContextHolder() {}

    /**
     * Get the context of the current virtual thread,
     * if no context is set, return a new context.
     *
     * @return context
     */
    public static Context getContext() {
        try {
            return CONTEXT_VALUE.get();
        } catch (NoSuchElementException e) {
            return new Context();
        }
    }

    /**
     * Check if the current virtual thread has a context.
     *
     * @return true if the current virtual thread has a context
     */
    public static boolean existContext() {
        try {
            return CONTEXT_VALUE.get() != null;
        } catch (NoSuchElementException e) {
            return false;
        }
    }

    /**
     * Clone the current context, if it is null, return a new context.
     *
     * @return cloned context
     */
    public static Context cloneContext() {
        try {
            return CONTEXT_VALUE.get().copy();
        } catch (NoSuchElementException e) {
            return new Context();
        }
    }

    /**
     * Run the action with the given context in a new virtual thread scope.
     *
     * @param newCtx    Context
     * @param action Action
     */
    public static void runWith(Context newCtx, Runnable action) {
        Objects.requireNonNull(newCtx, "Context must not be null");
        ScopedValue.where(CONTEXT_VALUE, newCtx).run(action);
    }

    /**
     * Call the operation with the given context in a new virtual thread scope.
     *
     * @param newCtx New context
     * @param op     Operation
     * @return operation result
     * @throws X if the operation throws
     */
    public static <T, X extends Throwable> T callWith(Context newCtx, ScopedValue.CallableOp<T, X> op) throws X {
        Objects.requireNonNull(newCtx, "Context must not be null");
        return ScopedValue.where(CONTEXT_VALUE, newCtx).call(op);
    }

    /**
     * Wrap the action to run with the cloned context of the current virtual thread.
     *
     * @param action Action
     * @return wrapped action
     */
    public static Runnable wrap(Runnable action) {
        Context snapshot = cloneContext();
        return () -> runWith(snapshot, action);
    }

}
