package io.softa.starter.message.shared;

import java.util.Objects;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;

/**
 * Tenant helpers for the messaging tables.
 * <p>
 * The platform's and the tenants' configuration live in separate tables: the
 * platform's ({@code Platform*}, the SMS tables) are not multi-tenant, a
 * tenant's are, and no read falls back from one to the other — the one
 * crossing is a tenant's mail relayed through the platform's send server when
 * the tenant has none. The send records are not multi-tenant either: they are
 * the queue the platform delivers for everyone, and {@code tenantId} on them
 * only says whom a send is filed under — {@link #PLATFORM} ({@code -1}) for
 * the platform's own.
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
     * Whether the current caller is the platform: multi-tenancy off (one owner), or a context whose
     * tenant is the platform tier — a platform operator's request, or system work with no tenant.
     */
    public static boolean platformCaller() {
        return !multiTenancyEnabled() || currentTenantOrPlatform() == PLATFORM;
    }

    /**
     * Refuse a tenant caller, for what only the platform reads or writes over a table that is not
     * multi-tenant — the send logs, the message quotas — as a second line behind the menus: a role still
     * holding such a page must not turn into a window onto every tenant's rows, nor raise its own ceiling.
     *
     * @param what names it in the error, e.g. {@code "The SMS send log"}, {@code "Message quotas"}
     */
    public static void requirePlatformCaller(String what) {
        if (!platformCaller()) {
            throw new PermissionException("{0} can only be read or changed from the platform.", what);
        }
    }

    /**
     * Whether a row filed under {@code ownerTenantId} in a non-multi-tenant table (send records) is the
     * current caller's to see: the platform sees every row, a tenant only its own.
     */
    public static boolean visibleToCurrentCaller(Long ownerTenantId) {
        return platformCaller() || Objects.equals(ownerTenantId, currentTenantOrPlatform());
    }
}
