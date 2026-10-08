package io.softa.framework.orm.domain;

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
 * @param hiddenSets sensitive field sets of the model no creating role grants
 */
@Schema(description = "Sensitive field sets a create form must not show")
public record CreateAccess(
        @Schema(description = "Sensitive field set ids hidden on a new record") Set<String> hiddenSets) {
}
