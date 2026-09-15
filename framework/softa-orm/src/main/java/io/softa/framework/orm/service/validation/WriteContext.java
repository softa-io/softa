package io.softa.framework.orm.service.validation;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

import io.softa.framework.orm.enums.AccessType;

/**
 * One row as a {@link ModelWriteValidator} sees it, plus the two ways to say no.
 *
 * <p>{@link #reject} accumulates: the validator names the field and the sentence, keeps checking, and
 * the framework throws once after every validator has run, so the caller learns about all the
 * problems in one round trip. {@link #fail} stops right away — for a precondition without which the
 * following checks would only produce noise or a {@code NullPointerException}.
 */
public final class WriteContext {

    private final String modelName;
    private final AccessType accessType;
    private final int rowIndex;
    private final Map<String, Object> row;
    private final Map<String, Object> patch;
    private final @Nullable Map<String, Object> originalRow;
    private final WriteValidationErrors errors;

    WriteContext(String modelName, AccessType accessType, int rowIndex, Map<String, Object> row,
                 Map<String, Object> patch, @Nullable Map<String, Object> originalRow, WriteValidationErrors errors) {
        this.modelName = modelName;
        this.accessType = accessType;
        this.rowIndex = rowIndex;
        this.row = row;
        this.patch = patch;
        this.originalRow = originalRow;
        this.errors = errors;
    }

    /**
     * A context for a single row outside the framework chain — what a unit test hands to a validator.
     * On update {@code row} is the merged row and doubles as the patch; use the five-argument form to
     * tell them apart.
     */
    public static WriteContext of(String modelName, AccessType accessType, Map<String, Object> row,
                                  @Nullable Map<String, Object> originalRow) {
        return of(modelName, accessType, row, row, originalRow);
    }

    /** A context with the request patch separate from the merged row. */
    public static WriteContext of(String modelName, AccessType accessType, Map<String, Object> row,
                                  Map<String, Object> patch, @Nullable Map<String, Object> originalRow) {
        return new WriteContext(modelName, accessType, 0, row, patch, originalRow, new WriteValidationErrors());
    }

    public String modelName() {
        return modelName;
    }

    public AccessType accessType() {
        return accessType;
    }

    /** Position of the row in the request batch, for messages that must say which one. */
    public int rowIndex() {
        return rowIndex;
    }

    /**
     * The row as it will be written: the request row on create; on update the patch merged onto the
     * stored row, so a field the request did not send still carries its current value.
     */
    public Map<String, Object> row() {
        return row;
    }

    /** Alias of {@link #row()} that reads better in an update validator. */
    public Map<String, Object> mergedRow() {
        return row;
    }

    /** The stored row on update; null on create. */
    public @Nullable Map<String, Object> originalRow() {
        return originalRow;
    }

    /** The row's id on update (from the stored row); on create the id the caller supplied, if any. */
    public @Nullable Serializable id() {
        Object id = originalRow != null ? originalRow.get("id") : row.get("id");
        return id instanceof Serializable s ? s : null;
    }

    /**
     * The fields the request actually sent — on update, only the patch; on create the same as
     * {@link #row()}. "May omit but may not clear" reads this: a key present with a blank value is
     * a clear, an absent key is an omission.
     */
    public Map<String, Object> patch() {
        return Collections.unmodifiableMap(patch);
    }

    /** Record a field-level rejection and keep going; thrown together with the others at the end. */
    public void reject(String field, String message) {
        errors.add(rowIndex, field, message);
    }

    /** Stop immediately: nothing after this check can be trusted to run. */
    public void fail(String message, Object... args) {
        throw new WriteValidationException(message, args);
    }

    /** Whether any validator has rejected anything in this write so far. */
    public boolean hasErrors() {
        return errors.hasErrors();
    }

    /** Everything rejected in this write so far — what a validator's unit test reads back. */
    public List<WriteValidationException.FieldError> errors() {
        return errors.errors();
    }
}
