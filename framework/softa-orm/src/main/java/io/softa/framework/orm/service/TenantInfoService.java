package io.softa.framework.orm.service;

import java.util.List;

import io.softa.framework.base.enums.Timezone;

/**
 * Framework SPI for multi-tenant runtime concerns. Returns only ids / booleans — never the
 * tenant entity, which lives in tenant-starter. The implementation
 * ({@code TenantInfoServiceImpl}) is provided by tenant-starter; framework consumers
 * (TenantAspect, ContextBuilder) depend only on this contract.
 */
public interface TenantInfoService {

    /**
     * Get all active tenant IDs.
     *
     * @return list of active tenant IDs
     */
    List<Long> getActiveTenantIds();

    /**
     * Whether the tenant is currently ACTIVE — the only state permitted to log in / operate.
     * Backed by the per-tenant cache, so it is cheap enough for a per-request gate.
     *
     * @param tenantId tenant id
     * @return true only if the tenant exists and its status is ACTIVE
     */
    boolean isTenantActive(Long tenantId);

    /**
     * Display name of a tenant, for screens shown BEFORE a session exists — above all the
     * "choose your company" step, where the caller has authenticated as a person but not yet
     * picked a membership, so no tenant-scoped read is available to them.
     *
     * <p>Default {@code null} so an implementation that predates this method still compiles;
     * callers must tolerate a missing name (fall back to the id) rather than assume one.
     */
    default String getTenantName(Long tenantId) {
        return null;
    }

    /**
     * Whether the tenant has finished being provisioned — its seed data is in place and it is safe to let
     * users in.
     *
     * <p>A <b>separate axis</b> from {@link #isTenantActive}, deliberately not folded into it. Operational
     * status answers "may this tenant operate at all" and is what a suspension flips; this answers "is this
     * tenant built yet". A freshly created tenant is ACTIVE from birth — it has to be, or its own seeders
     * could not write to it — so the two cannot share one flag.
     *
     * <p>Why login has to consult it: while seeding is in flight the tenant is half-built (its roles, org
     * masters and option items arrive over MQ, out of order), so a user let in early sees a workspace that is
     * missing pieces and can create records that reference masters which do not exist yet. It also makes
     * discarding a failed setup safe: if nobody can log in before READY, then every row in a not-yet-READY
     * tenant was written by a seeder, and deleting them cannot destroy anyone's work.
     *
     * <p>Defaulted to {@code true} rather than declared abstract, and that is about this being a published
     * SPI: an abstract addition breaks every external implementor's compile on an upgrade that changes nothing
     * they asked for. The default is also the honest answer for them — a deployment that tracks no provisioning
     * axis has no tenant that is "not built yet", so the gate degrades to {@link #isTenantActive} alone, which
     * is exactly the pre-existing behaviour.
     *
     * @param tenantId tenant id
     * @return true when the tenant is fully provisioned (or the deployment tracks no provisioning axis)
     */
    default boolean isTenantProvisioned(Long tenantId) {
        return true;
    }

    /**
     * Ids of the tenants whose setup has finished — active, suspended or closed. A suspended or closed tenant
     * keeps its data and may come back, so whatever keeps tenants' data current (the seed data) reaches it too.
     *
     * <p>Defaulted, like {@link #isTenantProvisioned}, so an implementation that predates it still compiles;
     * the default answers with the active tenants, the closest it can tell.
     *
     * @return tenant ids, in no particular order
     */
    default List<Long> getProvisionedTenantIds() {
        return getActiveTenantIds();
    }

    /**
     * The tenant's default country — its operating country — as an ISO 3166-1 alpha-2 code.
     *
     * <p>Default {@code null} so an implementation that predates this method still compiles; callers must
     * tolerate an unknown country.
     *
     * @param tenantId tenant id
     * @return the country code, or null when unknown
     */
    default String getDefaultCountry(Long tenantId) {
        return null;
    }

    /**
     * The tenant's default time zone: the calendar of a company that names none of its own.
     *
     * <p>Default {@code null} so an implementation that predates this method still compiles; callers fall back to
     * the server's zone when it is unknown.
     *
     * @param tenantId tenant id
     * @return the time zone, or null when unknown
     */
    default Timezone getDefaultTimezone(Long tenantId) {
        return null;
    }

    /**
     * Suspend a tenant — ACTIVE → SUSPENDED. See {@link #activate} for the contract these three share.
     *
     * @param tenantId tenant id
     */
    void deactivate(Long tenantId);

    /**
     * Reactivate a suspended tenant — SUSPENDED → ACTIVE.
     *
     * <p>These three transitions ({@code activate} / {@link #deactivate} / {@link #close}) are the
     * <b>only</b> sanctioned way to change a tenant's operational status, because each of them does two
     * things that a plain column write does not:
     *
     * <ol>
     *   <li>stamps the timestamp belonging to the target status and <b>clears the other two</b>, so exactly
     *       one is ever set and the trio stays a function of the status — an active tenant can never show a
     *       suspended time;</li>
     *   <li>evicts the tenant caches, so {@link #isTenantActive} and active-id filtering see the change at
     *       once. Users of a tenant leaving ACTIVE are then forced to re-login on their next request (the
     *       per-request gate rejects them and drops their session).</li>
     * </ol>
     *
     * Without the eviction a suspension does not take effect until the cache expires — the tenant's users
     * keep working meanwhile — which is why the status column must not be exposed as an editable field.
     *
     * @param tenantId tenant id
     */
    void activate(Long tenantId);

    /**
     * Close a tenant — ACTIVE / SUSPENDED → CLOSED. Terminal; data is retained. See {@link #activate} for
     * the shared contract.
     *
     * @param tenantId tenant id
     */
    void close(Long tenantId);

    /**
     * Make sure the platform tenant exists — the row whose id is
     * {@link io.softa.framework.base.constant.BaseConstant#PLATFORM_TENANT_ID}, ACTIVE, that platform
     * operators' accounts belong to.
     *
     * <p>Login checks the tenant of every account, the platform operators' included, so their accounts
     * cannot sign in until this row exists. It is not a provisioned tenant: nothing seeds it and nothing
     * moves its status later, so it is created ACTIVE and only ever by this method.
     *
     * <p>Creates, never repairs. A row already there — in any state, soft-deleted included — is left
     * exactly as it is; changing a live tenant is a decision for a person, not for a boot.
     *
     * <p>The default does nothing: an application without the tenant model has no tenant check at
     * login, so there is no row to need.
     *
     * @return true when this call created the row, false when it was already there
     */
    default boolean ensurePlatformTenant() {
        return false;
    }
}
