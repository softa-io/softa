package io.softa.framework.orm.domain;

import java.io.Serializable;
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
 */
@Schema(description = "The caller's access to one record: hidden / read-only sensitive sets and available actions")
public record RecordAccess(
        @Schema(description = "Record id") Serializable id,
        @Schema(description = "Sensitive field set ids hidden on this record") Set<String> hiddenSets,
        @Schema(description = "Sensitive field set ids visible but not editable on this record") Set<String> readonlySets,
        @Schema(description = "Actions available on this record") Set<AccessType> actions) {
}
