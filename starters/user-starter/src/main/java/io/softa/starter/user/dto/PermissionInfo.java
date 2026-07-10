package io.softa.starter.user.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.user.constant.RoleConstant;

/**
 * Runtime permission snapshot for one user — built once at login by
 * PermissionInfoEnricher and cached in Redis (key: perm:{tenantId}:user:{userId},
 * TTL 1h).
 *
 * Does NOT carry URL allowed set — URL → permissionId resolution lives in the
 * system-level EndpointIndex singleton (built once at app startup). Interceptor
 * looks up endpoint there, then checks the user's permissions Set.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "User permission snapshot (runtime cache)")
public class PermissionInfo {

    @Schema(description = "Role codes the user holds (display only; auth decisions use permissions / nav sets)")
    private Set<String> roleCodes;

    @Schema(description = "Navigation IDs visible to the user (flat; ancestors auto-expanded)")
    private Set<String> navigations;

    @Schema(description = "Permission IDs granted to the user")
    private Set<String> permissions;

    @Schema(description = "Model → scope rules aggregated across all role_navigation rows of same model. OR-combined at runtime.")
    private Map<String, List<ScopeRule>> modelScopeMap;

    @Schema(description = "Model → granted sensitive_field_set IDs. FieldFilter expands setIds → fieldCodes at response time.")
    private Map<String, Set<String>> modelSensitiveFieldSetsMap;

    /**
     * Single source of truth for the SUPER_ADMIN short-circuit consulted by
     * every layer (A/B/C/D + enricher). Returns true iff the user holds a
     * role whose {@code code = "SUPER_ADMIN"}.
     *
     * <p>Null-safe on every accessor; callers can write
     * {@code if (pi.isSuperAdmin()) return ...;} without sprinkling
     * {@code pi != null} guards. Static null-tolerant variant
     * {@link #isSuperAdmin(PermissionInfo)} for {@code pi} that itself may
     * be null.
     */
    public boolean isSuperAdmin() {
        return roleCodes != null && roleCodes.contains(RoleConstant.CODE_SUPER_ADMIN);
    }

    /** Static null-safe variant — pi == null treated as not SUPER_ADMIN
     *  (defensive default; pi == null shouldn't happen in production but
     *  callers occasionally accept it for graceful degradation). */
    public static boolean isSuperAdmin(PermissionInfo pi) {
        return pi != null && pi.isSuperAdmin();
    }
}
