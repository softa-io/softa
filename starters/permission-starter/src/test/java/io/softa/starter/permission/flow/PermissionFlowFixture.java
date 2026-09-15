package io.softa.starter.permission.flow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.index.EndpointIndex;
import io.softa.starter.permission.sensitive.SensitiveFieldSetCache;
import io.softa.starter.permission.spi.PermissionEndpointSource;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.ScopeType;
import io.softa.starter.permission.spi.SensitiveFieldSetSource;
import io.softa.starter.permission.spi.support.DefaultPermissionSnapshotProvider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fixture for in-process permission engine flow tests.
 *
 * <p><b>Zero user-starter dependency</b> (2026-07-17 full ⊥): seed rows are
 * expressed in the engine's OWN view DTOs
 * ({@link DefaultPermissionSnapshotProvider}'s {@code *View} projections) and
 * neutral local holders — never user-starter's {@code Navigation} / {@code Role}
 * / {@code NavigationType} entities. This keeps permission-starter tests carrying
 * no compile dependency on user-starter (mirrors the sacred main-scope invariant
 * into test scope). The engine only ever sees the view DTOs anyway (it reads RBAC
 * config by model name via 约定读), so the concrete producing entity is irrelevant.
 *
 * <p>Callers seed via the mutators, then call {@link #wire()} once to bootstrap
 * the real engine beans ({@link DefaultPermissionSnapshotProvider},
 * {@link EndpointIndex}, {@link SensitiveFieldSetCache}) against the seeded state,
 * stubbing only the framework boundary ({@link ModelService} / {@link CacheService}).
 */
public final class PermissionFlowFixture {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    // Seed rows. `roles` is public + the engine's own RoleView so a test can flip
    // active in place (RoleView is @Data); the rest are neutral local holders.
    public final List<DefaultPermissionSnapshotProvider.RoleView> roles = new ArrayList<>();
    private final List<NavSeed> navs = new ArrayList<>();
    private final List<PermSeed> perms = new ArrayList<>();
    private final List<GrantSeed> grants = new ArrayList<>();
    private final List<DataScopeSeed> dataScopeGrants = new ArrayList<>();
    private final List<SfsGrantSeed> sfsGrants = new ArrayList<>();
    private final List<UserRoleSeed> userRoleRels = new ArrayList<>();
    private final List<SfsSeed> sensitiveFieldSets = new ArrayList<>();

    // Wired beans (populated after wire())
    public SensitiveFieldSetCache sfsCache;
    public EndpointIndex endpointIndex;
    public DefaultPermissionSnapshotProvider provider;

    // Boundary mocks
    @SuppressWarnings("rawtypes")
    public ModelService modelService;
    public CacheService cacheService;

    // ─── neutral seed holders (no user-starter types) ───
    private record NavSeed(String id, String model, String parentId) {}
    private record PermSeed(String id, String navId, List<String> endpoints) {}
    private record GrantSeed(Long roleId, String navId, Set<String> permIds) {}
    private record DataScopeSeed(Long roleId, String model, JsonNode dataScopes) {}
    private record SfsGrantSeed(Long roleId, String sfsId) {}
    private record UserRoleSeed(Long userId, Long roleId) {}
    private record SfsSeed(String id, String model, List<String> fieldCodes) {}

    // ─── seed builders ───

    public void nav(String id, String model, String parentId) {
        navs.add(new NavSeed(id, model, parentId));
    }

    public void perm(String id, String navId, String... endpoints) {
        perms.add(new PermSeed(id, navId, List.of(endpoints)));
    }

    /** {@code name} is retained for call-site readability but unused — the engine's
     *  RoleView carries only id / code / active. */
    public void role(Long id, String name, String code, boolean active) {
        var v = new DefaultPermissionSnapshotProvider.RoleView();
        v.setId(id);
        v.setCode(code);
        v.setActive(active);
        roles.add(v);
    }

    public void grant(Long id, Long roleId, String navId,
                       Set<String> permissionIds,
                       Set<String> sfsIds,
                       List<ScopeRuleFixture> scopeFixtures) {
        grants.add(new GrantSeed(roleId, navId, permissionIds));

        // Split-table mirror — the engine reads scope / SFS grants from their own
        // models; model is resolved from the seeded nav.
        String model = modelForNav(navId);
        if (scopeFixtures != null && !scopeFixtures.isEmpty() && model != null) {
            ArrayNode arr = JSON.arrayNode();
            for (ScopeRuleFixture s : scopeFixtures) {
                ObjectNode obj = JSON.objectNode();
                obj.put("scopeType", s.type.name());
                if (s.expr != null) obj.set("scopeExpr", s.expr);
                arr.add(obj);
            }
            dataScopeGrants.add(new DataScopeSeed(roleId, model, arr));
        }
        if (sfsIds != null) {
            for (String sid : sfsIds) sfsGrants.add(new SfsGrantSeed(roleId, sid));
        }
    }

    /** Primary model of a seeded nav (nav must be added before its grant). */
    private String modelForNav(String navId) {
        for (NavSeed n : navs) if (n.id().equals(navId)) return n.model();
        return null;
    }

    public void bindUserToRole(Long relId, Long userId, Long roleId) {
        userRoleRels.add(new UserRoleSeed(userId, roleId));
    }

    public void sfs(String id, String model, List<String> fieldCodes) {
        sensitiveFieldSets.add(new SfsSeed(id, model, fieldCodes));
    }

    // ─── scope helper ───

    public static class ScopeRuleFixture {
        public final ScopeType type;
        public final JsonNode expr;
        public ScopeRuleFixture(ScopeType type, JsonNode expr) {
            this.type = type;
            this.expr = expr;
        }
        public static ScopeRuleFixture all() {
            return new ScopeRuleFixture(ScopeType.ALL, null);
        }
        public static ScopeRuleFixture custom(String... tuple) {
            ArrayNode arr = JSON.arrayNode();
            for (String s : tuple) arr.add(s);
            return new ScopeRuleFixture(ScopeType.CUSTOM, arr);
        }
        public static ScopeRuleFixture createdBySelf() {
            return new ScopeRuleFixture(ScopeType.CREATED_BY_SELF, null);
        }
    }

    // ─── wiring ───

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void wire() {
        modelService = mock(ModelService.class);
        cacheService = mock(CacheService.class);

        // DefaultPermissionSnapshotProvider reads RBAC config via the Class
        // projection (约定读). Project the neutral seeds into its view DTOs,
        // keyed by the same FlexQuery filters the provider issues.
        when(modelService.searchList(eq("UserRoleRel"), any(FlexQuery.class),
                eq(DefaultPermissionSnapshotProvider.UserRoleRelView.class))).thenAnswer(inv -> {
            Long userId = extractEqLongFromFlex(inv.getArgument(1), "userId");
            List<DefaultPermissionSnapshotProvider.UserRoleRelView> out = new ArrayList<>();
            for (UserRoleSeed r : userRoleRels) {
                if (userId == null || userId.equals(r.userId())) {
                    var v = new DefaultPermissionSnapshotProvider.UserRoleRelView();
                    v.setRoleId(r.roleId());
                    out.add(v);
                }
            }
            return out;
        });
        when(modelService.searchList(eq("Role"), any(FlexQuery.class),
                eq(DefaultPermissionSnapshotProvider.RoleView.class))).thenAnswer(inv -> {
            Set<Long> roleIds = extractInLongSetFromFlex(inv.getArgument(1), "id");
            List<DefaultPermissionSnapshotProvider.RoleView> out = new ArrayList<>();
            for (DefaultPermissionSnapshotProvider.RoleView r : roles) {
                if (!Boolean.TRUE.equals(r.getActive())) continue;
                if (roleIds == null || roleIds.contains(r.getId())) out.add(r);
            }
            return out;
        });
        when(modelService.searchList(eq("RoleNavigation"), any(FlexQuery.class),
                eq(DefaultPermissionSnapshotProvider.RoleNavigationView.class))).thenAnswer(inv -> {
            Set<Long> roleIds = extractInLongSetFromFlex(inv.getArgument(1), "roleId");
            List<DefaultPermissionSnapshotProvider.RoleNavigationView> out = new ArrayList<>();
            for (GrantSeed g : grants) {
                if (roleIds == null || roleIds.contains(g.roleId())) {
                    var v = new DefaultPermissionSnapshotProvider.RoleNavigationView();
                    v.setNavigationId(g.navId());
                    if (g.permIds() != null && !g.permIds().isEmpty()) {
                        ArrayNode arr = JSON.arrayNode();
                        g.permIds().forEach(arr::add);
                        v.setPermissionIds(arr);
                    }
                    out.add(v);
                }
            }
            return out;
        });
        when(modelService.searchList(eq("RoleDataScope"), any(FlexQuery.class),
                eq(DefaultPermissionSnapshotProvider.RoleDataScopeView.class))).thenAnswer(inv -> {
            Set<Long> roleIds = extractInLongSetFromFlex(inv.getArgument(1), "roleId");
            List<DefaultPermissionSnapshotProvider.RoleDataScopeView> out = new ArrayList<>();
            for (DataScopeSeed r : dataScopeGrants) {
                if (roleIds == null || roleIds.contains(r.roleId())) {
                    var v = new DefaultPermissionSnapshotProvider.RoleDataScopeView();
                    v.setModel(r.model());
                    v.setDataScopes(r.dataScopes());
                    out.add(v);
                }
            }
            return out;
        });
        when(modelService.searchList(eq("RoleSensitiveFieldSet"), any(FlexQuery.class),
                eq(DefaultPermissionSnapshotProvider.RoleSfsView.class))).thenAnswer(inv -> {
            Set<Long> roleIds = extractInLongSetFromFlex(inv.getArgument(1), "roleId");
            List<DefaultPermissionSnapshotProvider.RoleSfsView> out = new ArrayList<>();
            for (SfsGrantSeed r : sfsGrants) {
                if (roleIds == null || roleIds.contains(r.roleId())) {
                    var v = new DefaultPermissionSnapshotProvider.RoleSfsView();
                    v.setSensitiveFieldSetId(r.sfsId());
                    out.add(v);
                }
            }
            return out;
        });
        when(modelService.searchList(eq("Navigation"), any(FlexQuery.class),
                eq(DefaultPermissionSnapshotProvider.NavigationView.class))).thenAnswer(inv -> {
            List<DefaultPermissionSnapshotProvider.NavigationView> out = new ArrayList<>();
            for (NavSeed n : navs) {
                var v = new DefaultPermissionSnapshotProvider.NavigationView();
                v.setId(n.id());
                v.setParentId(n.parentId());
                out.add(v);
            }
            return out;
        });

        // Cache: always MISS so we exercise the DB-load path.
        when(cacheService.get(any(String.class), eq(PermissionInfo.class))).thenReturn(null);

        // SFS cache + endpoint index from inline SPI sources built straight from
        // the seeds (no ModelService / existModel guard, which the mocked
        // ModelManager here wouldn't satisfy).
        Map<String, String> navModel = new HashMap<>();
        for (NavSeed n : navs) if (n.model() != null) navModel.put(n.id(), n.model());

        sfsCache = new SensitiveFieldSetCache((SensitiveFieldSetSource) () -> {
            List<SensitiveFieldSetSource.SensitiveFieldSetDef> defs = new ArrayList<>();
            for (SfsSeed s : sensitiveFieldSets) {
                defs.add(new SensitiveFieldSetSource.SensitiveFieldSetDef(
                        s.id(), s.model(), new HashSet<>(s.fieldCodes()), null, Set.of()));
            }
            return defs;
        });
        sfsCache.reload();

        endpointIndex = new EndpointIndex((PermissionEndpointSource) () -> {
            List<PermissionEndpointSource.PermissionEndpointDef> defs = new ArrayList<>();
            for (PermSeed p : perms) {
                defs.add(new PermissionEndpointSource.PermissionEndpointDef(
                        p.id(), p.endpoints(),
                        p.navId() == null ? null : navModel.get(p.navId())));
            }
            return defs;
        });
        ReflectionTestUtils.invokeMethod(endpointIndex, "init");

        // No compiler: these flows exercise nav/permission derivation, and none of their fixture roles
        // scope the company model — so the company axis short-circuits to "unrestricted" before the
        // supplier is ever asked. A supplier yielding null is the same degradation a pure-enforce
        // deployment gets, so adding such a role to a fixture surfaces as an unrestricted grant rather
        // than an NPE in an unrelated test.
        provider = new DefaultPermissionSnapshotProvider(cacheService, modelService, sfsCache,
                () -> null,
                java.util.List.of(), List.of());
    }

    // ─── FlexQuery filter extraction helpers (framework types only) ───

    private static Long extractEqLongFromFlex(FlexQuery q, String field) {
        if (q == null || q.getFilters() == null) return null;
        return extractEqLong(q.getFilters(), field);
    }

    private static Long extractEqLong(io.softa.framework.orm.domain.Filters f, String field) {
        if (f == null) return null;
        io.softa.framework.orm.domain.FilterUnit u = f.getFilterUnit();
        if (u != null && field.equals(u.getField())
                && io.softa.framework.base.enums.Operator.EQUAL.equals(u.getOperator())
                && u.getValue() instanceof Number n) {
            return n.longValue();
        }
        if (f.getChildren() != null) {
            for (io.softa.framework.orm.domain.Filters child : f.getChildren()) {
                Long v = extractEqLong(child, field);
                if (v != null) return v;
            }
        }
        return null;
    }

    private static Set<Long> extractInLongSetFromFlex(FlexQuery q, String field) {
        if (q == null || q.getFilters() == null) return null;
        return extractInLongSet(q.getFilters(), field);
    }

    private static Set<Long> extractInLongSet(io.softa.framework.orm.domain.Filters f, String field) {
        if (f == null) return null;
        io.softa.framework.orm.domain.FilterUnit u = f.getFilterUnit();
        if (u != null && field.equals(u.getField())
                && io.softa.framework.base.enums.Operator.IN.equals(u.getOperator())
                && u.getValue() instanceof Iterable<?> vals) {
            Set<Long> out = new HashSet<>();
            for (Object o : vals) if (o instanceof Number n) out.add(n.longValue());
            return out;
        }
        if (u != null && field.equals(u.getField())
                && io.softa.framework.base.enums.Operator.EQUAL.equals(u.getOperator())
                && u.getValue() instanceof Number n) {
            Set<Long> out = new HashSet<>();
            out.add(n.longValue());
            return out;
        }
        if (f.getChildren() != null) {
            for (io.softa.framework.orm.domain.Filters child : f.getChildren()) {
                Set<Long> v = extractInLongSet(child, field);
                if (v != null) return v;
            }
        }
        return null;
    }
}
