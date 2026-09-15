package io.softa.starter.permission.interceptor;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.BuiltinRole;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.enums.SystemRole;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.ConfigurationException;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.service.ConsultantAccessChecker;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;
import io.softa.starter.permission.index.EndpointIndex;

/**
 * Endpoint gate — request-level access control.
 *
 * Flow:
 *   1. Match request URI against permission.public-uri-patterns (yml).
 *      Public endpoints (login / health / oauth callback) are allowed without auth.
 *   2. Short-circuit when caller is super-admin (system role).
 *   3. EndpointIndex.lookup(uri, method) → permissionId.
 *      Unmapped endpoints → 403 (defaults to denying unknown URLs).
 *   4. PermissionInfo.permissions.contains(permissionId) → 403 when missing.
 *
 * The row-scope filter (ScopeFilterAspect) and response field mask
 * (FieldFilter) run after this passes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private final AntPathMatcher matcher = new AntPathMatcher();

    /** Whether a consultant's authorization for this tenant still stands, asked per request.
     *  Optional — a deployment without consultants installs no
     *  implementation, and the consultant branch never fires there anyway. Field-injected: the
     *  constructor is RequiredArgs over finals. */
    @Autowired(required = false)
    private ConsultantAccessChecker consultantAccessChecker;
    private final EndpointIndex endpointIndex;
    private final PermissionSnapshotProvider snapshotProvider;
    /** Whitelist patterns bound via {@code @ConfigurationProperties} — see
     *  {@link PermissionInterceptorProperties} for why this isn't
     *  {@code @Value} (YAML list binding). */
    private final PermissionInterceptorProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        // Use servletPath (path INSIDE the app context) so the EndpointIndex
        // convention is app-context-agnostic. With server.servlet.context-path
        // = "/api/hcm":
        //   getRequestURI()  → "/api/hcm/Employee/searchPage"  (full URL)
        //   getServletPath() → "/Employee/searchPage"          (in-app path)
        // Public URI patterns also match against this in-app path so the yml
        // patterns don't have to be rewritten per-app.
        String uri = req.getServletPath();
        String method = req.getMethod();

        if (isPublic(uri)) return true;

        // Auth context populated by upstream filter (softa-web)
        Context ctx = ContextHolder.getContext();
        if (ctx == null || ctx.getUserId() == null) {
            throw new PermissionException("Authentication required for " + uri);
        }
        // Require tenantId before we cache PermissionInfo by (tenantId, userId).
        // Without this, a request whose upstream forgot to populate tenantId
        // produces a cache key of `perm:null:user:<id>` — a slot every
        // tenant collides on. Fail-closed and loudly so the upstream auth
        // misconfiguration is caught immediately.
        if (ctx.getTenantId() == null) {
            // Distinct exception type so monitoring can separate "user
            // lacks permission" (PermissionException — 403 user-facing)
            // from "auth context broken" (ConfigurationException — 5xx /
            // alertable). Both fail the request, but the operator signal
            // is different.
            log.error("Missing tenantId on authenticated request — userId={}, uri={}",
                    ctx.getUserId(), uri);
            throw new ConfigurationException("Authentication missing tenant context for " + uri);
        }

        // Authenticated-bypass: caller IS logged in (above check passed) but the
        // endpoint is exempt from the permission gate. Used for "self-service"
        // endpoints every user needs: /UserProfile/getMy*, /me/**,
        // /UserAccount/logout, /UserAccount/changeMyPassword, etc. Returned BEFORE
        // any snapshot work — bypassed endpoints don't consume the snapshot (and
        // /me builds its own on a cache miss), so there's no reason to read/build
        // it for them. Role codes are intentionally NOT bridged on bypass paths,
        // so {@code @RequireRole} on a whitelisted path still fails closed.
        if (matchAny(properties.getAuthenticatedBypassPatterns(), uri)) return true;

        // Non-bypass: build (cache-aside via the SnapshotProvider) the per-user
        // snapshot the gate needs. This same read warms the user's cache for any
        // later request.
        PermissionInfo pi = snapshotProvider.get(ctx.getTenantId(), ctx.getUserId());

        // Super-admin bypass — role-based, single source of truth. Bridge the
        // resolved role codes into the framework-layer Context so framework
        // aspects (e.g. {@code @RequireRole}) can gate on system roles without
        // depending on the user-starter permission model.
        bridgeRoleCodesToContext(ctx, pi);
        // Whether the consultant's authorization still stands — asked on every request, and asked
        // FIRST, ahead of every bypass below. A consultant's
        // access ends on a DATE and nobody edits anything when it lapses at midnight; cached with the
        // snapshot it would keep a lapsed consultant inside a customer's tenant for the rest of the TTL.
        //
        // Ahead of the admin branches because those return early. This check used to sit inside the
        // consultant branch, which is reached only when the caller holds neither admin code — so a
        // consultant membership that had somehow picked up TENANT_ADMIN took the admin branch and was
        // never asked whether its authorization still stood. Disable, revoke and expiry all stopped
        // applying to exactly the consultant with the most reach. The question is about the
        // MEMBERSHIP, not about which bypass the caller earns afterwards, so it belongs before all of
        // them.
        //
        // Deliberately after the authenticated-bypass patterns above: /me/**, the tenant list and the
        // self-service reads have to keep working, or the client that just learned its authorization
        // ended could not render that state or find the person's other tenants. Refusing everything
        // would strand them on a blank screen instead of the picker.
        if (PermissionInfo.isConsultant(pi) && consultantAccessChecker != null
                && !consultantAccessChecker.stillAuthorized(ctx.getUserId())) {
            log.info("Consultant authorization ended — userId={}, tenantId={}, uri={} {}",
                    ctx.getUserId(), ctx.getTenantId(), method, uri);
            throw new BusinessException(ResponseCode.CONSULTANT_AUTHORIZATION_ENDED,
                    "Your authorization for this tenant has ended.");
        }
        // Platform super-admin — cross-tenant (crossTenant is set in the bridge above and stays: the
        // account roster and provisioning span tenants by definition), but no longer a full bypass.
        //
        // Tenant business work belongs to the consultant now, who does it inside the customer that
        // authorized them and for as long as that authorization lasts. The platform administrator
        // keeps System and Studio. Matching against their snapshot — which platformAdminSnapshot
        // narrowed to exactly those — is what makes that a boundary rather than a hidden sidebar:
        // otherwise every tenant endpoint stays one typed URL away.
        if (PermissionInfo.isSuperAdmin(pi)) {
            return planBoundedBypass(pi, ctx, uri, method, BuiltinRole.SUPER_ADMIN);
        }
        // Tenant super-admin — bypasses the permission gate WITHIN its own tenant (tenant-isolated,
        // crossTenant stays false), but is denied platform-only Ops endpoints (billing / plan /
        // provisioning) which only SUPER_ADMIN may reach, and endpoints belonging to a module its
        // plan does not entitle.
        if (PermissionInfo.isTenantAdmin(pi)) {
            return planBoundedBypass(pi, ctx, uri, method, BuiltinRole.TENANT_ADMIN);
        }
        // Platform consultant — the same gate, reached by a different rule. A consultant's menus and
        // functions are defined as the tenant's current subscription in full, which is computed the
        // same way a tenant admin's are; they are two rules that agree today, not one rule.
        //
        // Its own named branch rather than folding CONSULTANT into isTenantAdmin(), because that
        // predicate is a bypass and a bypass has no dial: were consultants ever to be narrowed —
        // the platform deciding they should not reach payroll, say — there would be nothing to
        // change here without first unpicking them back out of the admin path, and every other
        // reader of "is a tenant admin" would have silently started answering yes for them.
        if (PermissionInfo.isConsultant(pi)) {
            // The authorization was checked above, before any bypass; this branch only decides the gate.
            return planBoundedBypass(pi, ctx, uri, method, BuiltinRole.CONSULTANT);
        }

        // EndpointIndex.lookup returns every permission id that lists this
        // endpoint in its `permission.endpoints` array (or matches the
        // standard CRUD derivation). The user is allowed if their permission
        // set intersects this candidate set — ANY granted permission opens
        // the endpoint. This is how shared lookup endpoints (e.g.
        // /Department/searchList used by both the Department admin page and
        // the Employee page's dept-tree panel) get reachable from multiple
        // business permissions.
        Set<String> candidatePermissions = endpointIndex.lookup(uri, method);
        if (candidatePermissions == null || candidatePermissions.isEmpty()) {
            throw new PermissionException("Endpoint not registered: " + method + " " + uri);
        }
        Set<String> userPermissions = pi.getPermissions();
        if (userPermissions == null || Collections.disjoint(userPermissions, candidatePermissions)) {
            // Detail (required-permission set) goes to server log so ops can
            // diagnose; the response carries only "missing permission for X"
            // so a probing client can't enumerate the permission graph by
            // poking endpoints and reading 403 bodies.
            log.warn("Missing permission — userId={}, uri={} {}, required any of: {}",
                    ctx.getUserId(), method, uri, candidatePermissions);
            throw new PermissionException("Missing permission for " + method + " " + uri);
        }
        return true;
    }

    /**
     * Copy the resolved role codes onto the framework-layer
     * {@link PermissionInfo} carried by the
     * Context, so framework aspects can evaluate {@code @RequireRole} without
     * importing the user-starter permission model (the Context field is the
     * decoupling SPI). Super-admin is expanded to hold every {@link SystemRole}
     * code — god-mode already short-circuits every other layer, so a
     * system-role gate must not be stricter for it.
     */
    private void bridgeRoleCodesToContext(Context ctx, PermissionInfo pi) {
        Set<String> codes = new HashSet<>();
        if (pi != null && pi.getRoleCodes() != null) codes.addAll(pi.getRoleCodes());
        if (PermissionInfo.isSuperAdmin(pi)) {
            for (SystemRole r : SystemRole.values()) codes.add(r.getCode());
            // NOT crossTenant. Reaching across tenants is a property of an operation, not of an
            // identity, so it is opted into where it is needed — @CrossTenant on the method, or an
            // explicit ContextUtils.inSystemContext / inTenantContext window — never granted here for
            // the whole request.
            //
            // This used to set crossTenant = true, which waived tenant isolation for every read AND
            // stopped AutofillFields from stamping tenant_id on every write. The reads made
            // tenant-scoped screens list other tenants' rows (a company switcher offering a dozen
            // unrelated tenants' legal entities was how it surfaced); the writes produced rows with no
            // tenant at all, which nobody but a super-admin can then see. It also made
            // SequenceServiceImpl reject every allocation, so a super-admin could not create a record
            // carrying an auto-numbered code.
            //
            // What still reaches across tenants, and how, now that this does not:
            //   - TenantInfo / TenantSubscription — not multiTenant models, never filtered.
            //   - Tenant provisioning — ContextUtils.inSystemContext().
            //   - Creating a tenant's first admin — ContextUtils.inTenantContext(targetTenant), with
            //     the globally-unique email check on @CrossTenant getUserByEmail before pinning.
            //   - The super-admin's account roster (admins of every tenant plus its own tenant's
            //     users) — a window the UserAccount search endpoints open around a scope filter they
            //     compute themselves; see UserAccountController.inRosterScope.
            // Menus, navigation, permission items and data-scope types are unaffected either way:
            // those models are not multiTenant, and the full-catalog view a super-admin gets comes
            // from the role-code bypass below, not from tenant isolation.
        }
        ctx.setRoleCodes(codes);
    }

    private boolean isPublic(String uri) {
        return matchAny(properties.getPublicUriPatterns(), uri);
    }

    /**
     * The gate an admin-shaped principal passes: bypasses per-permission checks inside its own
     * tenant, but is denied platform-only Ops endpoints and anything in a module the tenant's plan
     * does not entitle.
     *
     * <p>The plan match is enforcement, not decoration. Neither principal holds static nav grants, so
     * the downgrade cleanup has nothing to strip for either, and their snapshot's permission set — the
     * set matched here — is the only thing standing between a direct call and a dropped module's
     * endpoints.
     *
     * <p><b>An unregistered endpoint still bypasses.</b> That is what this path has always been for:
     * plenty of endpoints carry no permission mapping at all, and refusing those would turn a billing
     * gate into a broad outage. It is also why the coverage validator exists — the mapping gap is the
     * thing to close, not this allowance.
     *
     * @param principal which of the built-in roles is calling — named in the logs by its role code,
     *                  and the one input to {@link #deniedPlatformOnly}
     */
    private boolean planBoundedBypass(PermissionInfo pi, Context ctx, String uri, String method,
                                      BuiltinRole principal) {
        if (deniedPlatformOnly(principal) && matchAny(properties.getPlatformOnlyPatterns(), uri)) {
            log.warn("Platform-only endpoint denied to {} — userId={}, uri={} {}",
                    principal, ctx.getUserId(), method, uri);
            throw new PermissionException("Platform-admin only: " + method + " " + uri);
        }
        Set<String> candidates = endpointIndex.lookup(uri, method);
        if (candidates != null && !candidates.isEmpty()
                && Collections.disjoint(pi.getPermissions(), candidates)) {
            log.warn("Module not entitled for {} — userId={}, uri={} {}, required any of: {}",
                    principal, ctx.getUserId(), method, uri, candidates);
            throw new PermissionException("Missing permission for " + method + " " + uri);
        }
        return true;
    }

    /**
     * Whether the platform-only endpoints — billing, plan, provisioning, the consultant models — are
     * barred to this principal.
     *
     * <p>Exactly one is exempt: the platform administrator, who is who those endpoints exist FOR.
     * Everyone INSIDE a tenant is barred, a tenant admin and a consultant alike; refusing the
     * platform here would deny it its console and leave nobody able to provision anything.
     *
     * <p>Written as "not the platform" rather than as a list of the barred, so that a built-in role
     * added later is barred until somebody decides otherwise — fail-closed, which is the right
     * default for a whitelist of the platform's own operations. Package-private for the test that
     * pins this over every value of {@link BuiltinRole}.
     *
     * <p>This used to be a private enum beside this method, carrying a log label and this flag per
     * principal. {@link BuiltinRole} now names the same three in the framework's base, so the enum
     * was a second copy of an identity that already had one home — and the flag reduces to one
     * comparison. The logs now print the role code itself, which is also what {@code role.code}
     * and a user's {@code roleCodes} carry, so one term greps across all three.
     */
    static boolean deniedPlatformOnly(BuiltinRole principal) {
        return principal != BuiltinRole.SUPER_ADMIN;
    }

    private boolean matchAny(List<String> patterns, String uri) {
        if (patterns == null) return false;
        for (String pattern : patterns) {
            if (pattern == null || pattern.isEmpty()) continue;
            if (matcher.match(pattern, uri)) return true;
        }
        return false;
    }
}
