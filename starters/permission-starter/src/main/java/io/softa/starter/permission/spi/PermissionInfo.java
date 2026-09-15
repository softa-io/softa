package io.softa.starter.permission.spi;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.swagger.v3.oas.annotations.media.Schema;
import io.softa.framework.base.enums.BuiltinRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Runtime permission snapshot for one user — built once at login by the enrich
 * side ({@code PermissionInfoEnricher}) and cached in Redis
 * (key: {@code perm-v2:{tenantId}:user:{userId}}, TTL 1h).
 *
 * <p>2026-07-14: unified — the former {@code user-starter} rich {@code dto.PermissionInfo}
 * merged into this single framework-base type, so the enforce side
 * ({@code permission-starter} interceptor + data-plane) can read the snapshot via
 * the {@code PermissionSnapshotProvider} SPI without depending on {@code user-starter}.
 * {@code Context} carries this type; both build and enforce share it.
 *
 * <p>Does NOT carry the URL allowed-set — URL → permissionId resolution lives in the
 * {@code EndpointIndex} singleton (permission-starter). The interceptor looks up the
 * endpoint there, then checks {@link #permissions}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "User permission snapshot (runtime cache)")
public class PermissionInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Role code that identifies a platform super-admin (cross-tenant, all menus + platform Ops). */
    public static final String CODE_SUPER_ADMIN = BuiltinRole.SUPER_ADMIN.getCode();

    /** Role code that identifies a tenant super-admin — bypasses permission/scope WITHIN its own
     *  tenant (tenant-isolated, no cross-tenant), but is denied platform-only endpoints (billing /
     *  provisioning / cross-tenant Ops; see {@code PermissionInterceptorProperties.platformOnlyPatterns}). */
    public static final String CODE_TENANT_ADMIN = BuiltinRole.TENANT_ADMIN.getCode();

    /**
     * Platform consultant working inside a client company under a dated grant.
     *
     * <p>A third kind of principal, not a third admin. The distinction is the whole point: a
     * consultant sees the tenant's data without restriction — that is what they were brought in to
     * work on — but the MENUS they get are whatever the tenant's subscription includes, no more.
     * Folding them into {@link #isAdmin()} would hand them screens the tenant has not bought.
     *
     * <p>Not a role with grant rows either. A downgrade physically deletes over-plan role grants and
     * a re-upgrade does not restore them (the entitlement cleanup's deliberate design), and this
     * role is not editable and does not appear in the tenant's role management — so a single
     * downgrade would strip it permanently with nobody able to put it back. Deriving the menus from
     * the subscription at request time has no such failure mode, and makes an upgrade take effect
     * the moment it is bought.
     */
    public static final String CODE_CONSULTANT = BuiltinRole.CONSULTANT.getCode();

    @Schema(description = "Role codes the user holds (display + super-admin check; auth decisions use permissions / nav sets)")
    private Set<String> roleCodes;

    @Schema(description = "Legacy grouped permission codes (model → codes); kept for framework aspects")
    private Map<String, Set<String>> permissionCodes;

    @Schema(description = "Navigation IDs visible to the user (flat; ancestors auto-expanded)")
    private Set<String> navigations;

    @Schema(description = "Permission IDs granted to the user (flat set; interceptor intersects with endpoint→permission)")
    private Set<String> permissions;

    @Schema(description = "Model → scope rules aggregated across all role_navigation rows of same model. OR-combined at runtime.")
    private Map<String, List<ScopeRule>> modelScopeMap;

    @Schema(description = "Model → granted sensitive_field_set IDs. FieldFilter expands setIds → fieldCodes at response time.")
    private Map<String, Set<String>> modelSensitiveFieldSetsMap;

    /**
     * Legal entities this user's roles may reach, unioned across roles. Bounds every multi-company
     * model, independently of {@link #modelScopeMap} — which companies a role may reach is a property
     * of the role, not of any one model.
     *
     * <p><b>Three states, and the difference between two of them is the point:</b>
     * <ul>
     *   <li>{@code null} — <b>unrestricted</b>. No company axis applies. This is what a role nobody
     *       has configured resolves to, so the grant stays opt-in and shipping it empties nobody's
     *       screen.</li>
     *   <li><b>empty</b> — <b>no company at all</b>: every multi-company read matches nothing. Only an
     *       explicit configuration produces this, never the absence of one.</li>
     *   <li>non-empty — exactly those companies.</li>
     * </ul>
     *
     * <p>"Not configured" and "configured to nothing" used to collapse into the same empty set, which
     * made the second inexpressible: a role meant to reach no company at all — a self-service employee
     * role, say — could only be written as the absence of a grant, and absence means unrestricted.
     * Splitting them is what lets a company axis be mandatory for the roles that need one without
     * forcing every existing role to be reconfigured first.
     *
     * <p>Cached with the rest of the snapshot, so a read pays no query for it. Roles change → the
     * snapshot is evicted → this is rebuilt with them.
     */
    private Set<Long> grantedCompanyIds;

    /**
     * Single source of truth for the SUPER_ADMIN short-circuit consulted by every
     * layer (route-admission + data-plane + enricher). True iff the user holds the
     * {@link #CODE_SUPER_ADMIN} role.
     *
     * <p>Null-safe: callers can write {@code if (pi.isSuperAdmin()) ...} without
     * {@code pi != null} guards; static {@link #isSuperAdmin(PermissionInfo)} tolerates
     * a null {@code pi}.
     */
    public boolean isSuperAdmin() {
        return BuiltinRole.SUPER_ADMIN.heldBy(roleCodes);
    }

    /** Static null-tolerant variant — {@code pi == null} treated as not super-admin. */
    public static boolean isSuperAdmin(PermissionInfo pi) {
        return pi != null && pi.isSuperAdmin();
    }

    /** True iff the user holds the {@link #CODE_TENANT_ADMIN} role — a tenant-scoped super-admin. */
    public boolean isTenantAdmin() {
        return BuiltinRole.TENANT_ADMIN.heldBy(roleCodes);
    }

    /** Static null-tolerant variant of {@link #isTenantAdmin()}. */
    public static boolean isTenantAdmin(PermissionInfo pi) {
        return pi != null && pi.isTenantAdmin();
    }

    /**
     * True iff the user is any admin — platform {@link #CODE_SUPER_ADMIN} or tenant
     * {@link #CODE_TENANT_ADMIN}. Both bypass the data-plane checks (row scope, field mask, write
     * guard); the difference is reach: SUPER_ADMIN is cross-tenant + platform Ops, TENANT_ADMIN is
     * confined to its own tenant (via {@code crossTenant=false}) and denied platform-only endpoints.
     */
    public boolean isAdmin() {
        return isSuperAdmin() || isTenantAdmin();
    }

    /** Static null-tolerant variant of {@link #isAdmin()}. */
    public static boolean isAdmin(PermissionInfo pi) {
        return pi != null && pi.isAdmin();
    }

    /** True iff the user is acting as a consultant inside this tenant — see {@link #CODE_CONSULTANT}. */
    public boolean isConsultant() {
        return BuiltinRole.CONSULTANT.heldBy(roleCodes);
    }

    /** Static null-tolerant variant of {@link #isConsultant()}. */
    public static boolean isConsultant(PermissionInfo pi) {
        return pi != null && pi.isConsultant();
    }

    /**
     * Whether this principal reads the tenant's data without row scope, field masking or write
     * guards — the admins, plus a consultant.
     *
     * <p>Separate from {@link #isAdmin()} on purpose, and this is the line the whole consultant
     * design rests on: the DATA plane treats a consultant like an admin, the MENU plane does not.
     * Every data-plane check asks this; endpoint and navigation checks keep asking
     * {@code isAdmin()}, so a consultant is still bounded by what the tenant's plan includes.
     * Merging the two would silently sell the tenant's whole menu to whoever authorized a
     * consultant.
     */
    public boolean hasFullDataAccess() {
        return isAdmin() || isConsultant();
    }

    /** Static null-tolerant variant of {@link #hasFullDataAccess()}. */
    public static boolean hasFullDataAccess(PermissionInfo pi) {
        return pi != null && pi.hasFullDataAccess();
    }
}
