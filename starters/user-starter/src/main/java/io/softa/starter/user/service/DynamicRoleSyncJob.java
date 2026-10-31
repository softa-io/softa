package io.softa.starter.user.service;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.domain.Page;

/**
 * Re-evaluates role.dynamic_filter for every active role and synchronizes
 * the user_role table (source=DYNAMIC rows only). Runs on a Spring cron
 * schedule (default: every night at 02:00).
 *
 * Algorithm (per role with non-null dynamic_filter):
 *   1. Run the filter as ORM query → set of currently-matching user IDs.
 *   2. Diff against existing user_role rows where role_id=R AND source=DYNAMIC.
 *   3. INSERT new matches, DELETE rows no longer matching.
 *   4. Manual rows (source=MANUAL) are never touched.
 *   5. Affected user IDs are batch-evicted from PermissionInfo cache.
 *
 * INNER JOIN employee in the underlying SQL means pure users (no employee
 * record) are NEVER matched by dynamic rules — they can only be assigned
 * roles MANUALLY.
 */
public interface DynamicRoleSyncJob {

    /**
     * Sync a specific role's dynamic membership immediately (admin-triggered).
     *
     * <p>Runs under the caller's {@code Context.tenantId} — the role is
     * resolved and all downstream queries (Employee, UserRoleRel) filter by
     * that tenant. Callers arriving from an HTTP request already have a
     * populated tenant context; other callers must set it themselves.
     *
     * @return count of inserts + deletes performed
     */
    int syncRole(Long roleId);

    /**
     * Sync every dynamic role in the caller's tenant. Assumes
     * {@code Context.tenantId} is set — throws when it isn't (fail-loud
     * rather than silently no-op on empty results).
     *
     * <p>Cron-triggered execution uses {@code DynamicRoleSyncCronHandler}
     * (in hcm-app) as the fan-out point: the handler enumerates active
     * tenants once, then invokes this method under a per-tenant cloned
     * context so every query inside runs single-tenant.
     */
    void syncAll();

    /**
     * Re-evaluate every dynamic role's membership for a single user.
     * Intended for domain-event bridges that see an HR change (transfer /
     * hire / status flip) and need the affected user's dynamic-role
     * assignments to refresh without waiting for the next cron tick.
     *
     * <p>Runs the same delta logic as {@link #syncRole} but scoped to a
     * single (tenant, user) pair: loads every dynamic role in the tenant,
     * checks whether this user satisfies each rule now vs. the current
     * {@code user_role_rel.DYNAMIC} rows, inserts / deletes accordingly.
     * MANUAL rows are never touched.
     *
     * <p>{@code tenantId} is required — bridges call this from Pulsar
     * consumers where the incoming {@code Context} may not carry a tenant.
     * The impl clones the context, forces {@code tenantId=<param>} and
     * clears {@code crossTenant} / {@code skipPermissionCheck} before
     * running the body, so the param truly scopes the execution.
     *
     * <p>Framework has no idea what a "domain change" means — callers are
     * expected to be domain-specific bridges that translate their own
     * business events into this call.
     *
     * @return count of inserts + deletes performed
     */
    int syncMembershipForUser(Long tenantId, Long userId);

    /**
     * Who a dynamic rule would grant, a page at a time, without writing anything: the members a sync of
     * a role with this rule would write — employees the rule matches whose account is Active — in
     * employee code order, each read with {@code fields}.
     *
     * <p>Lets an editor show the result of a rule before it is saved, worked out by the same code that
     * writes the grants, so the two cannot disagree. Runs under the caller's {@code Context.tenantId}
     * and permissions, as an admin-triggered {@link #syncRole} does.
     *
     * @param dynamicFilter the rule, in the form {@code Role.dynamicFilter} stores it; null or not an
     *                      array matches nobody
     * @param fields        the Employee fields to read for each member; the id is always read
     */
    Page<Map<String, Object>> previewMembers(JsonNode dynamicFilter, List<String> fields,
                                             Integer pageNumber, Integer pageSize);
}
