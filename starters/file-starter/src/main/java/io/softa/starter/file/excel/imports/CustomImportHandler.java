package io.softa.starter.file.excel.imports;

import java.util.List;
import java.util.Map;

import io.softa.framework.orm.constant.FileConstant;

/**
 * Custom import business hook.
 *
 * <p>Contract:
 * implementations may mutate row values in-place and may mark a row as failed by setting
 * {@link FileConstant#FAILED_REASON}, but must not add, remove, reorder or replace row objects.
 * This keeps the current rows list aligned with the copied original rows for later failure export.</p>
 */
public interface CustomImportHandler {

    /**
     * Handle import rows in-place.
     *
     * @param rows import rows, mutable in-place only
     * @param env environment variables
     * @param validateOnly true while the validation-only pipeline runs: the handler still runs — its
     *                     checks are part of the validation feedback the user sees — but must skip
     *                     everything that writes (provisioning accounts, scheduling jobs, calling out).
     *                     Passed as an argument rather than left in {@code env} so an implementation
     *                     neither needs the reserved key's name nor has to decide what an absent or
     *                     non-Boolean value means.
     */
    void handleImportData(List<Map<String, Object>> rows, Map<String, Object> env, boolean validateOnly);

    /**
     * Called once after the rows have been persisted, on a real import only.
     *
     * <p>For the work that needs the rows to exist: a projection keyed on the new id, a segment of
     * history, a sub-record hanging off the row. The ORM fills {@code id} into these same maps
     * during insert, so each persisted row carries it by the time this runs; a row that failed
     * never got one, so check before using it.
     *
     * <p>This exists because there was no other way to do it. Registering a transaction
     * synchronisation from {@link #handleImportData} does not work and never did: the only
     * transaction in an import is the one INSIDE the persistence call, which starts after that
     * method has returned, so {@code afterCommit} is registered against nothing and silently
     * skipped. Both the synchronous and the asynchronous path behave that way. Handlers written
     * against the older assumption have been quietly losing that work.
     *
     * <p>Not called in validation mode, which writes nothing, and not called when persistence
     * threw. A failure here does not undo the import: the rows are committed, so an implementation
     * should be idempotent and forgiving rather than assume it can roll anything back.
     *
     * <p>The two sides are handed over separately because a persisted row cannot be asked which it
     * was. Both carry an {@code id} by the time this runs — the matched row has the stored id put
     * back onto the very map that was passed in — and nothing else distinguishes them. A snapshot
     * taken in {@link #handleImportData} does not help either: neither side has an id yet there.
     * Passed as two arguments rather than announced through {@code env}, for the reason the flag
     * above is an argument — an implementation should not have to know a reserved key's name or
     * decide what an absent value means.
     *
     * @param created rows that matched nothing stored and were inserted
     * @param updated rows that matched a stored row and were written onto it
     * @param env     environment variables, the same map {@link #handleImportData} was given
     */
    default void afterImportData(List<Map<String, Object>> created,
                                 List<Map<String, Object>> updated,
                                 Map<String, Object> env) {
        // Most handlers have nothing to do after the write.
    }

    /**
     * @deprecated Implement {@link #handleImportData(List, Map, boolean)} instead — without the flag a
     *         handler cannot tell a real import from a validation run, so anything it writes happens
     *         twice: once while the user is only asking whether the file is valid. Kept so an existing
     *         implementation still compiles; the pipeline never calls this overload.
     */
    @Deprecated(forRemoval = true)
    default void handleImportData(List<Map<String, Object>> rows, Map<String, Object> env) {
        handleImportData(rows, env, false);
    }
}
