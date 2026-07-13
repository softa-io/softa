package io.softa.starter.message.shared;

import java.util.List;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;

/**
 * Tenant visibility scopes for platform-overlay reads.
 * <p>
 * Messaging config tables follow a shared-plus-overlay model: {@code tenant_id
 * = 0} rows are platform-level and visible to every tenant, {@code > 0} rows
 * belong to one tenant. Overlay reads therefore run {@code @CrossTenant} (to
 * suppress the implicit single-tenant filter) and constrain explicitly to
 * {@code tenantId IN (0, currentTenant)} via this helper.
 */
public final class TenantScopes {

    private TenantScopes() {}

    /**
     * The tenants visible to the current caller: the platform tier (0) plus
     * the caller's own tenant when one is set. Safe in single-tenant
     * deployments where the context carries no tenant.
     */
    public static List<Long> currentPlusPlatform() {
        Context ctx = ContextHolder.getContext();
        Long tenant = ctx != null ? ctx.getTenantId() : null;
        return (tenant == null || tenant == 0L) ? List.of(0L) : List.of(0L, tenant);
    }
}
