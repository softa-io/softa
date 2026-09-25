package io.softa.starter.permission.scope;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolve {@code deptId → idPath} for {@code DepartmentSubtreeScopeContributor}
 * and {@code ManagedDepartmentsScopeContributor}, backed by a per-tenant Redis
 * cache of the department tree.
 *
 * <h3>Cache model (Redis cache-aside)</h3>
 * The whole tenant's department tree is cached as a single
 * {@code {deptId → idPath}} map under {@code dept-idpath:{tenantId}}
 * (only id + idPath are stored). Lookups are read-through:
 * <ol>
 *   <li>Load the map from Redis.</li>
 *   <li>On miss, load the tree once from the DB, populate Redis (TTL), serve.</li>
 *   <li>Look the deptId(s) up in the in-memory map — no per-id DB round-trips.</li>
 * </ol>
 *
 * <p>The predecessor {@code DepartmentTreePathCache} (R1) was a JVM Map reloaded
 * on {@code DepartmentChangedEvent}; Spring events are JVM-scoped, so multi-pod
 * deployments went stale on pods that missed the write. Redis is shared across
 * pods, so this cache doesn't have that problem. {@link #evict} (called by the
 * HR app's {@code DepartmentChangedEvent} adapter) drops
 * {@code dept-idpath:{tenantId}} immediately on any structural change
 * (create / move / delete / head-change), so a moved department is reflected on
 * the next lookup. {@link #CACHE_TTL_SECONDS} is a backstop for the edge case
 * where a change commits with no tenant bound to evict against.
 *
 * <h3>Query semantics</h3>
 * The tree is loaded under {@code skipPermissionCheck=true} — the caller's
 * DEPT_SUBTREE / MANAGED_DEPARTMENTS grant on the parent model (e.g. Employee)
 * authorises the dept resolution; requiring a separate Department scope grant
 * would be a UX trap. The tenant filter is NOT skipped, so a tenant only ever
 * caches / reads its own departments — cross-tenant idPaths never leak. When no
 * tenant is bound the resolver returns nothing (fail-closed) rather than load
 * across tenants.
 *
 * <h3>Behaviour on unknown id</h3>
 * Missing / soft-deleted / cross-tenant deptId → {@link Optional#empty()} for
 * {@link #idPathOf}, silently dropped from {@link #idPathsOf}. Contributors
 * treat empty as fail-closed for the enclosing rule.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepartmentIdPathResolver {

    private static final String DEPARTMENT_MODEL = "Department";
    private static final String FIELD_ID = "id";

    /** Per-tenant cache key prefix: {@code dept-idpath:{tenantId}}. HR-domain
     *  key, kept out of softa's RedisConstant. */
    private static final String CACHE_KEY_PREFIX = "dept-idpath:";

    /** Backstop expiry for the cached tree. Structural changes evict eagerly via
     *  {@link #evict} (the HR app's event adapter); this TTL only covers the edge
     *  case where a change commits with no tenant bound to evict against. Tunable. */
    private static final int CACHE_TTL_SECONDS = RedisConstant.FIVE_MINUTES;

    private static final TypeReference<Map<Long, String>> TREE_TYPE = new TypeReference<>() {};

    private final ModelService<Long> modelService;
    private final CacheService cacheService;

    /** Resolve one dept id. Empty when unknown / soft-deleted / cross-tenant. */
    public Optional<String> idPathOf(Long deptId) {
        if (deptId == null) return Optional.empty();
        String idPath = tenantTree().get(deptId);
        return (idPath != null && !idPath.isEmpty()) ? Optional.of(idPath) : Optional.empty();
    }

    /**
     * Resolve a batch of dept ids from the cached tree. Unknown ids drop
     * silently. Callers must not rely on positional correspondence with the
     * input collection.
     */
    public List<String> idPathsOf(Collection<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) return List.of();
        Set<Long> distinct = new HashSet<>(deptIds.size());
        for (Long id : deptIds) if (id != null) distinct.add(id);
        if (distinct.isEmpty()) return List.of();

        Map<Long, String> tree = tenantTree();
        List<String> out = new ArrayList<>(distinct.size());
        for (Long id : distinct) {
            String idPath = tree.get(id);
            if (idPath != null && !idPath.isEmpty()) out.add(idPath);
        }
        return out;
    }

    /** Drop the cached department tree for a tenant; the next lookup reloads it.
     *  Public so the domain's event adapter (the HR app's {@code DepartmentChangedEvent}
     *  listener) can evict on any structural change. {@link #CACHE_TTL_SECONDS} is
     *  the backstop when no tenant is bound to evict against. */
    public void evict(Long tenantId) {
        if (tenantId == null) return;
        try {
            cacheService.clear(CACHE_KEY_PREFIX + tenantId);
        } catch (Throwable t) {
            log.warn("DepartmentIdPathResolver — evicting dept-idpath:{} failed; TTL will expire it", tenantId, t);
        }
    }

    /**
     * The current tenant's {@code {deptId → idPath}} map, read-through cached in
     * Redis. Returns an empty map when no tenant is bound (fail-closed) or on any
     * cache/DB error — the enclosing scope rule then degrades to empty.
     */
    private Map<Long, String> tenantTree() {
        Long tenantId = ContextHolder.existContext() ? ContextHolder.getContext().getTenantId() : null;
        if (tenantId == null) {
            log.debug("DepartmentIdPathResolver — no tenant bound; skipping dept-tree resolution");
            return Map.of();
        }
        String key = CACHE_KEY_PREFIX + tenantId;
        try {
            Map<Long, String> cached = cacheService.get(key, TREE_TYPE);
            if (cached != null) return cached;
        } catch (Throwable t) {
            log.warn("DepartmentIdPathResolver — Redis read for {} failed; falling back to DB", key, t);
        }
        Map<Long, String> tree = loadTreeFromDb();
        try {
            cacheService.save(key, tree, CACHE_TTL_SECONDS);
        } catch (Throwable t) {
            log.warn("DepartmentIdPathResolver — Redis write for {} failed; served from DB", key, t);
        }
        return tree;
    }

    /**
     * Load the current tenant's whole department tree ({@code id}, {@code idPath})
     * in one query. Runs with {@code skipPermissionCheck=true} (scope bypass) but
     * keeps the tenant filter, so only this tenant's departments load.
     */
    private Map<Long, String> loadTreeFromDb() {
        Context ctx = ContextHolder.getContext();
        boolean previous = ctx != null && ctx.isSkipPermissionCheck();
        if (ctx != null) ctx.setSkipPermissionCheck(true);
        try {
            List<Map<String, Object>> rows = modelService.searchList(
                    DEPARTMENT_MODEL, new FlexQuery(List.of(FIELD_ID, IdPath.FIELD)));
            Map<Long, String> tree = new HashMap<>(rows.size());
            for (Map<String, Object> row : rows) {
                Long id = coerceLong(row.get(FIELD_ID));
                Object path = row.get(IdPath.FIELD);
                if (id != null && path instanceof CharSequence cs && !cs.isEmpty()) {
                    tree.put(id, cs.toString());
                }
            }
            return tree;
        } catch (Throwable t) {
            log.warn("DepartmentIdPathResolver — dept-tree DB load failed; treating tree as empty", t);
            return Map.of();
        } finally {
            if (ctx != null) ctx.setSkipPermissionCheck(previous);
        }
    }

    private static Long coerceLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof CharSequence cs && !cs.isEmpty()) {
            try {
                return Long.parseLong(cs.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
