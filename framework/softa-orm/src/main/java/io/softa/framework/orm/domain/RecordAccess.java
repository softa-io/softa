package io.softa.framework.orm.domain;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.enums.AccessType;

/**
 * What the caller may do with one existing record — for a form deciding which sections to show, which
 * to show read-only, and which buttons to offer, before anything is attempted.
 *
 * <p>Answered per record because the answer differs per record: the roles reaching one employee may
 * grant a sensitive section the roles reaching the next one do not. A form reading the user's merged
 * permissions instead shows buttons that fail when pressed and sections that come back empty.
 *
 * @param id           the record
 * @param hiddenSets   sensitive field sets of the model the caller may not see on this record
 * @param readonlySets sensitive field sets the caller may see on this record but not edit
 * @param actions      the actions the caller may perform on this record ({@code UPDATE}, {@code DELETE})
 * @param hiddenFields   the sensitive fields no reading role that reaches this record grants, keyed by the
 *                       model each belongs to
 * @param readonlyFields the sensitive fields that show but no editing role that reaches this record grants,
 *                       the same way. Judged field by field, as the mask and the write check are, so a set
 *                       counted editable because one of its fields is can still have read-only fields here
 *
 * <p>The field maps answer what a set id alone cannot: which column or input a set takes with it. A
 * screen that lays its sections out by set needs only the ids; one that judges each field on its own —
 * a list cell, a field outside any declared section — needs the fields, and the set definitions are
 * not something every caller may read.
 */
@Schema(description = "The caller's access to one record: hidden / read-only sensitive sets and available actions")
public record RecordAccess(
        @Schema(description = "Record id") Serializable id,
        @Schema(description = "Sensitive field set ids hidden on this record") Set<String> hiddenSets,
        @Schema(description = "Sensitive field set ids visible but not editable on this record") Set<String> readonlySets,
        @Schema(description = "Actions available on this record") Set<AccessType> actions,
        @Schema(description = "Fields hidden on this record, by the model they belong to") Map<String, Set<String>> hiddenFields,
        @Schema(description = "Fields visible but not editable on this record, by model") Map<String, Set<String>> readonlyFields) {

    /** Sets and actions only, with no field breakdown — nothing is hidden or read-only by field. */
    public RecordAccess(Serializable id, Set<String> hiddenSets, Set<String> readonlySets, Set<AccessType> actions) {
        this(id, hiddenSets, readonlySets, actions, Map.of(), Map.of());
    }
}
