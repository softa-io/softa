package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.event.RoleGrantChangedEvent;
import io.softa.starter.user.event.RoleNavigationChangedEvent;
import io.softa.starter.user.event.UserRoleRelChangedEvent;
import io.softa.starter.user.service.PermissionCacheInvalidator;
import io.softa.starter.user.service.PermissionInfoEnricher;
import io.softa.starter.user.service.UserRoleRelService;

/**
 * Redis-cache invalidator + event listeners that drive it.
 *
 * <p>{@code evict*} methods compute the canonical cache key
 * ({@link PermissionInfoEnricher#cacheKey}) and call
 * {@link CacheService#clear} — single or batch — to delete the entry.
 * The cache then misses on next request and re-fills via the underlying
 * {@link PermissionInfoEnricher} (DB read).
 *
 * <p>No tenant-wide eviction exists: {@link CacheService} doesn't expose
 * SCAN-style pattern delete, and the failure modes for "evict every user
 * in this tenant" all reduce to either (a) feed an explicit user-id set
 * through {@link #evictBatch}, or (b) wait out the 1h Redis TTL. Bulk
 * admin ops do the former; system-level seed data (navigation /
 * permission / sensitive_field_set) only changes via redeployment so a
 * restart flushes the in-memory indexes and the TTLs cover the rest.
 *
 * <h3>Event → eviction fan-out</h3>
 * <ul>
 *   <li>{@link UserRoleRelChangedEvent}    → {@link #evictBatch} for the users
 *       whose role set changed.</li>
 *   <li>{@link RoleNavigationChangedEvent} → {@link #evictByRole} for the role
 *       whose nav/permission/scope grant changed.</li>
 * </ul>
 *
 * <p>Domain-flavored triggers (business events that shift a user's cached
 * domain context without touching user_role_rel) do
 * NOT live here — the framework has no business knowing what specific
 * domain events exist. Instead, business modules add their own bridge
 * bean that listens to their domain event and calls {@link #evictOne} on
 * this API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionCacheInvalidatorImpl implements PermissionCacheInvalidator {

    private final UserRoleRelService userRoleRelService;
    private final CacheService cacheService;

    // ────────────────────── public evict API ──────────────────────

    @Override
    public void evictOne(Long tenantId, Long userId) {
        if (userId == null) return;
        if (tenantId == null) {
            // Publisher lost tenant context. Cache stays stale for this
            // user until 1h TTL. Log at WARN so ops has a trail — the
            // old silent no-op would mask broken scheduled-job /
            // async-pool paths.
            log.warn("PermissionInfo cache evict skipped — null tenantId (userId={}); "
                    + "publisher missing ContextHolder.callWith(bootstrapCtx, ...)", userId);
            return;
        }
        String key = PermissionInfoEnricher.cacheKey(tenantId, userId);
        try {
            cacheService.clear(key);
            log.debug("PermissionInfo cache evict — key={}", key);
        } catch (Throwable t) {
            log.warn("PermissionInfo cache evict failed — key={}", key, t);
        }
    }

    @Override
    public void evictBatch(Long tenantId, Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return;
        if (tenantId == null) {
            // See evictOne for rationale.
            log.warn("PermissionInfo cache evict batch skipped — null tenantId "
                    + "(userIds count={}); publisher missing "
                    + "ContextHolder.callWith(bootstrapCtx, ...)", userIds.size());
            return;
        }
        List<String> keys = new ArrayList<>(userIds.size());
        for (Long uid : userIds) {
            if (uid != null) keys.add(PermissionInfoEnricher.cacheKey(tenantId, uid));
        }
        if (keys.isEmpty()) return;
        try {
            cacheService.clear(keys);
            log.debug("PermissionInfo cache evict batch — tenantId={}, count={}", tenantId, keys.size());
        } catch (Throwable t) {
            log.warn("PermissionInfo cache evict batch failed — tenantId={}, count={}",
                    tenantId, keys.size(), t);
        }
    }

    @Override
    public void evictByRole(Long tenantId, Long roleId) {
        if (tenantId == null || roleId == null) return;
        Set<Long> userIds = usersHoldingRole(roleId);
        if (userIds.isEmpty()) {
            log.debug("PermissionInfo cache evict by role — no users hold roleId={}; nothing to do", roleId);
            return;
        }
        evictBatch(tenantId, userIds);
    }

    // ────────────────────── event listeners ──────────────────────

    /**
     * {@code @TransactionalEventListener(AFTER_COMMIT)} — fire only after
     * the publishing transaction commits. Plain
     * {@code @EventListener} would run synchronously inside
     * {@code publishEvent()}, which is called from inside a
     * {@code @Transactional} controller method — the eviction fires
     * <em>before</em> commit, so a concurrent read between eviction and
     * commit misses Redis, reads pre-commit DB state, and refills Redis
     * with stale data. Waiting for AFTER_COMMIT closes that race.
     *
     * <p>{@code fallbackExecution = true} keeps the listener working when
     * the publisher runs outside a transaction (e.g. tests, cron jobs
     * that manage their own tx boundary) — falls back to synchronous
     * execution, matching the pre-fix behaviour for the non-transactional
     * path.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserRoleRelChanged(UserRoleRelChangedEvent ev) {
        if (ev.userIds() == null || ev.userIds().isEmpty()) {
            // Empty fan-out — nothing actionable. Publishers must include
            // a concrete user-id set; relying on tenant-wide invalidation
            // here was a fail-loud crutch that the cache TTL (1h) covers
            // in practice. Log so the offending publisher is identifiable.
            log.warn("PermissionCacheInvalidator — UserRoleRelChangedEvent (tenantId={}) carried no userIds; "
                    + "no eviction performed (relying on 1h cache TTL)", ev.tenantId());
            return;
        }
        evictBatch(ev.tenantId(), ev.userIds());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRoleNavigationChanged(RoleNavigationChangedEvent ev) {
        if (ev.roleId() == null) return;
        // evictByRole resolves the user set + batch-clears internally.
        evictByRole(ev.tenantId(), ev.roleId());
    }

    /**
     * Data-dimension grant change (role_data_scope / role_sensitive_field_set).
     * Same per-role eviction as {@link #onRoleNavigationChanged} — kept as a
     * separate listener so the scope/SFS write path stays decoupled from the
     * nav/permission one.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRoleGrantChanged(RoleGrantChangedEvent ev) {
        if (ev.roleId() == null) return;
        evictByRole(ev.tenantId(), ev.roleId());
    }

    // ────────────────────── helpers ──────────────────────

    /**
     * Look up the users currently holding a role. Bounded by typical role
     * cardinality (a few hundred holders); pulls user ids only.
     *
     * <p>Runs with {@code skipPermissionCheck=true} — cache invalidation is
     * system-level and must see every role holder regardless of the
     * publisher's scope. Without this, a non-super-admin who edits a role
     * would only invalidate the subset of holders their own scope rules
     * allow them to see; the remaining holders keep stale PermissionInfo
     * for up to the 1h Redis TTL.
     *
     * <p>Tenant filter is <em>not</em> skipped — {@code evictByRole} takes
     * a {@code tenantId} and expects per-tenant scoping. Only scope /
     * field / write-guard checks are suppressed.
     */
    private Set<Long> usersHoldingRole(Long roleId) {
        Context ctx = ContextHolder.getContext();
        boolean previous = ctx != null && ctx.isSkipPermissionCheck();
        if (ctx != null) ctx.setSkipPermissionCheck(true);
        try {
            List<UserRoleRel> rels = userRoleRelService.searchList(
                    new Filters().eq(UserRoleRel::getRoleId, roleId));
            Set<Long> userIds = new HashSet<>(rels.size());
            for (UserRoleRel r : rels) {
                if (r.getUserId() != null) userIds.add(r.getUserId());
            }
            return userIds;
        } finally {
            if (ctx != null) ctx.setSkipPermissionCheck(previous);
        }
    }

}
