package io.softa.starter.message.shared;

import java.util.List;
import java.util.function.Supplier;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;

/**
 * Tenant-tier helpers for the messaging tables.
 * <p>
 * {@code multiTenant} messaging rows live in one of two fully separate
 * namespaces: {@code tenant_id = -1} rows form the <b>platform tier</b> —
 * owned by the platform operator and invisible to tenant-scoped reads — and
 * {@code > 0} rows belong to one tenant. There is no overlay: templates are
 * copied to tenants at provisioning, and platform server/provider configs are
 * reached only by the dispatchers' silent fallback ({@code @CrossTenant})
 * when a tenant has none of its own.
 */
public final class TenantScopes {

    /** The platform tier's tenant id — see {@link BaseConstant#PLATFORM_TENANT_ID}. */
    public static final long PLATFORM = BaseConstant.PLATFORM_TENANT_ID;

    private TenantScopes() {}

    /**
     * Whether multi-tenancy is enabled for this deployment. Null-safe against
     * a missing {@link SystemConfig} (plain unit tests), where it reports
     * {@code false} — the single-tenant behaviour.
     */
    public static boolean multiTenancyEnabled() {
        return SystemConfig.env != null && SystemConfig.env.isEnableMultiTenancy();
    }

    /**
     * The current caller's tenant id, or {@link #PLATFORM} when the context
     * carries none (platform operations, background jobs, single-tenant).
     */
    public static long currentTenantOrPlatform() {
        Context ctx = ContextHolder.getContext();
        Long tenant = ctx != null ? ctx.getTenantId() : null;
        return tenant == null ? PLATFORM : tenant;
    }

    /**
     * The tenants whose rows the current caller's <b>records</b> may
     * reference: the caller's own tenant plus the platform tier. Only for
     * id-addressed replay reads ({@code findVisibleById}) — send/receive
     * records legitimately point at platform configs picked by the
     * dispatcher fallback. Never used for list surfaces: platform rows stay
     * invisible to tenants.
     */
    public static List<Long> currentPlusPlatform() {
        long tenant = currentTenantOrPlatform();
        return tenant == PLATFORM ? List.of(PLATFORM) : List.of(PLATFORM, tenant);
    }

    /**
     * Run a send record's insert in the context that owns the record. The ORM stamps the
     * record's tenant from the context, and only while the context is not cross-tenant, so the
     * insert runs non-cross-tenant: the record belongs to the caller's tenant. A caller sending
     * for another tenant pins that tenant first ({@code ContextUtils.inTenantContext}). A
     * platform-scope send from a context with no tenant — a login code asked for before any
     * session exists — is filed under the platform tier.
     */
    public static <T> T inRecordOwnerContext(boolean platformScope, Supplier<T> insert) {
        Context current = ContextHolder.getContext();
        boolean platformWithoutTenant = platformScope && current.getTenantId() == null;
        if (!platformWithoutTenant && !current.isCrossTenant()) {
            return insert.get();
        }
        Context owner = ContextHolder.cloneContext();
        if (platformWithoutTenant) {
            owner.setTenantId(PLATFORM);
        }
        owner.setCrossTenant(false);
        return ContextHolder.callWith(owner, insert::get);
    }
}
