package io.softa.starter.permission.interceptor;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.service.EndpointAccessChecker;
import io.softa.starter.permission.index.EndpointIndex;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;

/**
 * {@link EndpointAccessChecker} by the rules {@link PermissionInterceptor} applies to a request, asked of
 * each user's own permission snapshot instead of the caller's:
 * <ul>
 *   <li>an endpoint the public or authenticated-bypass patterns cover is open to every user;</li>
 *   <li>a platform-only endpoint is closed inside a customer tenant to everyone but the platform
 *       administrator;</li>
 *   <li>otherwise a user needs a permission that lists the endpoint — the admin principals included, whose
 *       snapshot carries the permissions of their tenant's plan; an endpoint no permission lists is open
 *       to the platform administrator alone.</li>
 * </ul>
 * A user without a snapshot cannot call it: the gate fails closed on one too. A consultant's authorization
 * lapsing is checked by the gate on each request and is not repeated here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotEndpointAccessChecker implements EndpointAccessChecker {

    private final AntPathMatcher matcher = new AntPathMatcher();

    private final EndpointIndex endpointIndex;
    private final PermissionSnapshotProvider snapshotProvider;
    private final PermissionInterceptorProperties properties;

    @Override
    public Set<Long> withoutAccess(Collection<Long> userIds, String method, String path) {
        Long tenantId = ContextHolder.existContext() ? ContextHolder.getContext().getTenantId() : null;
        List<Long> ids = userIds == null ? List.of() : userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || matchAny(properties.getPublicUriPatterns(), path)
                || matchAny(properties.getAuthenticatedBypassPatterns(), path)) {
            return Set.of();
        }
        if (tenantId == null) {
            // Snapshots are per tenant; without one nobody's can be read, and nobody can be said to pass.
            log.warn("Endpoint access of users {} asked outside a tenant — {} {}", ids, method, path);
            return new LinkedHashSet<>(ids);
        }
        boolean platformOnly = matchAny(properties.getPlatformOnlyPatterns(), path);
        boolean customerTenant = !BaseConstant.PLATFORM_TENANT_ID.equals(tenantId);
        Set<String> candidates = endpointIndex.lookup(path, method);
        Set<Long> without = new LinkedHashSet<>();
        for (Long userId : ids) {
            if (!this.canCall(snapshotProvider.get(tenantId, userId), candidates, platformOnly, customerTenant)) {
                without.add(userId);
            }
        }
        return without;
    }

    private static boolean canCall(PermissionInfo pi, Set<String> candidates, boolean platformOnly,
                                   boolean customerTenant) {
        if (pi == null) {
            return false;
        }
        boolean platformAdmin = PermissionInfo.isSuperAdmin(pi);
        if (platformOnly && customerTenant && !platformAdmin) {
            return false;
        }
        if (candidates == null || candidates.isEmpty()) {
            return platformAdmin;
        }
        return pi.getPermissions() != null && !Collections.disjoint(pi.getPermissions(), candidates);
    }

    private boolean matchAny(List<String> patterns, String path) {
        if (patterns == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isEmpty() && matcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}
