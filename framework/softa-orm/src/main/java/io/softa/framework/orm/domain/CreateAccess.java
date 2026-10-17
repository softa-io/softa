package io.softa.framework.orm.domain;

import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Which sensitive sections a create form may show.
 *
 * <p>A record that does not exist yet belongs to no role's rows, so it cannot be judged as
 * {@link RecordAccess} judges one. The form shows a sensitive set when some role that may create the
 * model grants it; whether the new record actually lands in that role's rows is checked when it is
 * saved, and refused there if not.
 *
 * @param hiddenSets   sensitive field sets of the model no creating role grants
 * @param hiddenFields the sensitive fields no creating role grants, keyed by the model each belongs to
 */
@Schema(description = "Sensitive field sets a create form must not show")
public record CreateAccess(
        @Schema(description = "Sensitive field set ids hidden on a new record") Set<String> hiddenSets,
        @Schema(description = "Fields hidden on a new record, by the model they belong to") Map<String, Set<String>> hiddenFields) {

    /** Sets only, with no field breakdown. */
    public CreateAccess(Set<String> hiddenSets) {
        this(hiddenSets, Map.of());
    }
}
