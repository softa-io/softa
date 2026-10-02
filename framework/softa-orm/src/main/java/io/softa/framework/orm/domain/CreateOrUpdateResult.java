package io.softa.framework.orm.domain;

import java.util.List;
import java.util.Map;

/**
 * Which of a batch's rows were matched against stored data, and which were not.
 *
 * <p>The distinction is made while deciding how to write each row and is not recoverable afterwards:
 * a created row and an updated one both come out of the write carrying an {@code id}, because the
 * matched row has the stored id put back onto the very map that was passed in. A caller that needs
 * to know which is which therefore has to be told, and this is the telling.
 *
 * <p>The lists hold the caller's own row maps, not copies, in the order the batch was given.
 *
 * @param created rows that matched nothing stored
 * @param updated rows that matched a stored row
 */
public record CreateOrUpdateResult(List<Map<String, Object>> created,
                                   List<Map<String, Object>> updated) {

    /** Nothing was written — an empty batch, or a rule that writes nothing. */
    public static CreateOrUpdateResult empty() {
        return new CreateOrUpdateResult(List.of(), List.of());
    }

    /** Every row is a create: the caller already knows none of them could match. */
    public static CreateOrUpdateResult allCreated(List<Map<String, Object>> rows) {
        return new CreateOrUpdateResult(rows == null ? List.of() : rows, List.of());
    }

    /** Every row is an update, for the same reason in the other direction. */
    public static CreateOrUpdateResult allUpdated(List<Map<String, Object>> rows) {
        return new CreateOrUpdateResult(List.of(), rows == null ? List.of() : rows);
    }
}
