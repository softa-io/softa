package io.softa.starter.user.filter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.SystemRole;
import io.softa.framework.base.exception.ConfigurationException;
import io.softa.framework.base.exception.PermissionException;
import io.softa.starter.user.dto.PermissionInfo;
import io.softa.starter.user.service.EndpointIndex;
import io.softa.starter.user.service.PermissionInfoEnricher;

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
    private final EndpointIndex endpointIndex;
    private final PermissionInfoEnricher permissionInfoEnricher;
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

        // Authenticated-bypass: caller IS logged in (above check passed) but
        // the endpoint is exempt from permission lookup. Used for
        // "self-service" endpoints every user needs: /UserProfile/getMyUserInfo,
        // /me/uiContext, /UserAccount/logout, /UserAccount/changeMyPassword,
        // etc. These return the caller's own data so there's no business
        // permission to gate on.
        if (matchAny(properties.getAuthenticatedBypassPatterns(), uri)) return true;

        // Super-admin bypass — role-based, single source of truth.
        // Any role with {@code code = "SUPER_ADMIN"} on the user short-
        // circuits the whole check chain (A/B/C/D layers all consult the
        // same {@code pi.roleCodes} set, so the behaviour is uniform).
        PermissionInfo pi = permissionInfoEnricher.enrich(ctx.getTenantId(), ctx.getUserId());
        // Bridge the resolved role codes into the framework-layer Context so
        // framework aspects (e.g. @RequireRole) can gate on system roles
        // without depending on the user-starter permission model. Done for
        // EVERY authenticated request that reaches a permission check — i.e.
        // NOT for public / authenticated-bypass endpoints, which return above
        // before this runs, so @RequireRole on a whitelisted path fails closed.
        bridgeRoleCodesToContext(ctx, pi);
        if (PermissionInfo.isSuperAdmin(pi)) return true;

        // EndpointIndex.lookup returns every permission id that lists this
        // endpoint in its `permission.endpoints` array (or matches the
        // standard CRUD derivation). The user is allowed if their permission
        // set intersects this candidate set — ANY granted permission opens
        // the endpoint. This is how shared lookup endpoints (e.g.
        // /Department/searchList used by both the Department admin page and
        // the Employee page's dept-tree panel) get reachable from multiple
        // business permissions.
        java.util.Set<String> candidatePermissions = endpointIndex.lookup(uri, method);
        if (candidatePermissions == null || candidatePermissions.isEmpty()) {
            throw new PermissionException("Endpoint not registered: " + method + " " + uri);
        }
        java.util.Set<String> userPermissions = pi.getPermissions();
        if (userPermissions == null || java.util.Collections.disjoint(userPermissions, candidatePermissions)) {
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
     * {@link io.softa.framework.base.context.PermissionInfo} carried by the
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
        }
        io.softa.framework.base.context.PermissionInfo base =
                new io.softa.framework.base.context.PermissionInfo();
        base.setRoleCodes(codes);
        ctx.setPermissionInfo(base);
    }

    private boolean isPublic(String uri) {
        return matchAny(properties.getPublicUriPatterns(), uri);
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
