package io.softa.starter.user.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

/**
 * Request body for {@code POST /admin/user-role/bulk-revoke} — remove a set of
 * {@code user_role_rel} rows by id.
 *
 * <p>Routed through the typed {@code UserRoleRelService} (NOT the generic
 * {@code /UserRoleRel/deleteByIds} ORM path) so the revoke inherits the
 * system-role last-holder guard AND the {@code UserRoleRelChangedEvent} cache
 * eviction — the generic path bypasses both, leaving revoked users' cached
 * permissions live until the 1h TTL.
 */
@Schema(description = "user_role_rel ids to revoke")
public record BulkRevokeRequest(
        @NotEmpty
        @Schema(description = "user_role_rel row ids to delete")
        List<Long> relIds
) {
}
