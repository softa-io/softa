package io.softa.framework.orm.service;

import java.util.NoSuchElementException;
import java.util.Objects;

import io.softa.framework.orm.enums.AccessType;

/**
 * The action a read is performed for, when it is not a plain read.
 *
 * <p>Row scope is granted per action: the rows a caller may update are the rows of the roles that
 * hold the update action, which need not be the rows they may read. But the query that answers
 * "may I update these ids" is itself a read — it counts the ids back through the ordinary read path
 * — and so is an export. Neither can say what it is reading for through the read API, whose
 * signatures carry no action. This carries it instead, for the extent of one call.
 *
 * <p>Outside any binding the action is {@link AccessType#READ}, so every caller that does not know
 * about this class keeps reading as it always has.
 */
public final class AccessScope {

    private static final ScopedValue<AccessType> CURRENT = ScopedValue.newInstance();

    private AccessScope() {
    }

    /** The action in effect; {@link AccessType#READ} when none is bound. */
    public static AccessType current() {
        try {
            return CURRENT.get();
        } catch (NoSuchElementException e) {
            return AccessType.READ;
        }
    }

    /**
     * Call the operation with {@code accessType} as the action every read inside it is performed for.
     *
     * @param accessType the action
     * @param op the operation
     * @return the operation's result
     * @throws X if the operation throws
     */
    public static <T, X extends Throwable> T callAs(AccessType accessType, ScopedValue.CallableOp<T, X> op) throws X {
        Objects.requireNonNull(accessType, "Access type must not be null");
        return ScopedValue.where(CURRENT, accessType).call(op);
    }

    /** The model the bound action is performed on, when the binding names one. */
    private static final ScopedValue<String> MODEL = ScopedValue.newInstance();

    /**
     * The action a read of {@code model} is performed for: the bound action when the binding names no
     * model or names this one, and a plain {@link AccessType#READ} for any other model read along the way.
     *
     * <p>An export is the export of one model. Its related columns are reads of other models, which the
     * exporting role reads as it reads them anywhere — not by an export permission on each of them, which
     * it rarely holds and which emptied every related column.
     */
    public static AccessType currentFor(String model) {
        AccessType bound = current();
        if (bound == AccessType.READ || !MODEL.isBound() || MODEL.get().equals(model)) {
            return bound;
        }
        return AccessType.READ;
    }

    /** Call the operation with {@code accessType} as the action on {@code model} — see {@link #currentFor}. */
    public static <T, X extends Throwable> T callAs(AccessType accessType, String model,
                                                   ScopedValue.CallableOp<T, X> op) throws X {
        Objects.requireNonNull(accessType, "Access type must not be null");
        Objects.requireNonNull(model, "Model must not be null");
        return ScopedValue.where(CURRENT, accessType).where(MODEL, model).call(op);
    }

    /** Run the action with {@code accessType} in effect — see {@link #callAs}. */
    public static void runAs(AccessType accessType, Runnable action) {
        Objects.requireNonNull(accessType, "Access type must not be null");
        ScopedValue.where(CURRENT, accessType).run(action);
    }
}
