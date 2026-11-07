package io.softa.starter.message.quota.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.quota.entity.TenantMessageQuota;

/**
 * CRUD and limit resolution for {@link TenantMessageQuota}. All write paths
 * are guarded to the platform scope — quota is configured by platform
 * operations, never by a tenant.
 */
public interface TenantMessageQuotaService extends EntityService<TenantMessageQuota, Long> {

    /**
     * The resolved monthly limits for one quota bucket: the tenant's row when
     * present, each null limit falling back to the deployment default
     * ({@code message.quota.*}); a null resolved value means unlimited.
     * Read with permission checks skipped — this runs when a send record is
     * prepared, in the record's context, and the quota registry is
     * platform-owned data no caller needs a grant for.
     *
     * @param tenantId the quota bucket (-1 = the platform's own quota)
     */
    ResolvedLimits resolveLimits(long tenantId);

    /**
     * Reject — with an explanatory error — any quota write attempted outside
     * the platform scope in a multi-tenant deployment.
     */
    void assertPlatformScope();

    /** Resolved monthly ceilings; null = unlimited. */
    record ResolvedLimits(Long mailMonthlyLimit, Long smsMonthlyLimit) {}
}
