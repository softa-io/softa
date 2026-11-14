package io.softa.framework.base.message;

/**
 * Tier-selection policy for one templated message send (mail or SMS) in a
 * multi-tenant deployment: which tier's template renders the content and
 * which tier's default server/provider carries it. The two tiers are fully
 * separate namespaces — tenants own copies of their templates (initialized at
 * provisioning), the platform ({@code tenant_id = -1}) owns its own; there is
 * no overlay or fallback on the template axis. An explicit config/template id
 * on the request still wins over the policy.
 * <p>
 * Carried on {@code SendMailDTO.scope} / {@code SendSmsDTO.scope};
 * {@code null} means {@link #TENANT}. With multi-tenancy disabled there is a
 * single namespace and the value has no effect. The scope also selects the monthly-quota bucket the send consumes:
 * {@code PLATFORM} sends draw on the platform's own quota, never a tenant's.
 */
public enum MessageScope {

    /**
     * Default. Resolve the current tenant's template; route via the tenant's
     * default server/provider, falling back to the (tenant-invisible)
     * platform default when the tenant has none.
     */
    TENANT,

    /**
     * Platform tier only: template and default server/provider both resolve
     * from the platform tier ({@code tenant_id = -1}). For platform-owned
     * messages — billing, security, compliance — that must neither be
     * re-worded by a tenant template nor routed through tenant-controlled
     * infrastructure.
     */
    PLATFORM
}
