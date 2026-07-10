package io.softa.starter.user.flow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.Navigation;
import io.softa.starter.user.entity.Permission;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.RoleDataScope;
import io.softa.starter.user.entity.RoleNavigation;
import io.softa.starter.user.entity.RoleSensitiveFieldSet;
import io.softa.starter.user.entity.SensitiveFieldSet;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.NavigationType;
import io.softa.starter.user.enums.ScopeType;
import io.softa.starter.user.filter.SensitiveFieldSetCache;
import io.softa.starter.user.scope.ScopeApplicabilityResolver;
import io.softa.starter.user.scope.ScopeContributor;
import io.softa.starter.user.scope.ScopeRuleCompiler;
import io.softa.starter.user.scope.contributor.CreatedBySelfScopeContributor;
import io.softa.starter.user.scope.contributor.CustomScopeContributor;
import io.softa.starter.user.service.EndpointIndex;
import io.softa.starter.user.service.NavigationModelResolver;
import io.softa.starter.user.service.PermissionInfoEnricher;
import io.softa.starter.user.service.RoleDataScopeService;
import io.softa.starter.user.service.RoleNavigationService;
import io.softa.starter.user.service.RoleSensitiveFieldSetService;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserRoleRelService;
import io.softa.starter.user.service.impl.NavigationModelResolverImpl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fixture for in-process permission flow tests.
 *
 * <p>Wires real user-starter beans (enricher, endpoint index, nav resolver,
 * scope compiler, SFS cache, interceptor) and stubs the framework boundary
 * ({@link ModelService} + {@link CacheService}) plus per-service search
 * calls with fixture rows. Callers add navigation / permission / role /
 * grant / user_role_rel rows via the mutators, then call {@link #wire()}
 * once to bootstrap the beans against the seeded state.
 */
public final class PermissionFlowFixture {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    // Seed rows
    public final List<Navigation> navigations = new ArrayList<>();
    public final List<Permission> permissions = new ArrayList<>();
    public final List<Role> roles = new ArrayList<>();
    public final List<RoleNavigation> grants = new ArrayList<>();
    public final List<RoleDataScope> dataScopeGrants = new ArrayList<>();
    public final List<RoleSensitiveFieldSet> sfsGrants = new ArrayList<>();
    public final List<UserRoleRel> userRoleRels = new ArrayList<>();
    public final List<SensitiveFieldSet> sensitiveFieldSets = new ArrayList<>();

    // Wired beans (populated after wire())
    public NavigationModelResolverImpl navResolver;
    public SensitiveFieldSetCache sfsCache;
    public EndpointIndex endpointIndex;
    public PermissionInfoEnricher enricher;
    public ScopeApplicabilityResolver applicability;
    public ScopeRuleCompiler scopeCompiler;

    // Boundary mocks
    @SuppressWarnings("rawtypes")
    public ModelService modelService;
    public CacheService cacheService;
    public RoleService roleService;
    public UserRoleRelService userRoleRelService;
    public RoleNavigationService roleNavigationService;
    public RoleDataScopeService roleDataScopeService;
    public RoleSensitiveFieldSetService roleSensitiveFieldSetService;

    // Optional contributor list — defaults to Created + Custom.
    public List<ScopeContributor> scopeContributors = new ArrayList<>();

    // ─── seed builders ───

    public Navigation nav(String id, NavigationType type, String model, String parentId) {
        Navigation n = new Navigation();
        n.setId(id);
        n.setType(type);
        n.setModel(model);
        n.setParentId(parentId);
        navigations.add(n);
        return n;
    }

    public Permission perm(String id, String navId, String... endpoints) {
        Permission p = new Permission();
        p.setId(id);
        p.setNavigationId(navId);
        if (endpoints.length > 0) {
            ArrayNode arr = JSON.arrayNode();
            for (String e : endpoints) arr.add(e);
            p.setEndpoints(arr);
        }
        permissions.add(p);
        return p;
    }

    public Role role(Long id, String name, String code, boolean active) {
        Role r = new Role();
        r.setId(id);
        r.setName(name);
        r.setCode(code);
        r.setActive(active);
        roles.add(r);
        return r;
    }

    public RoleNavigation grant(Long id, Long roleId, String navId,
                                Set<String> permissionIds,
                                Set<String> sfsIds,
                                List<ScopeRuleFixture> scopeFixtures) {
        RoleNavigation rn = new RoleNavigation();
        rn.setId(id);
        rn.setRoleId(roleId);
        rn.setNavigationId(navId);
        if (permissionIds != null && !permissionIds.isEmpty()) {
            ArrayNode arr = JSON.arrayNode();
            permissionIds.forEach(arr::add);
            rn.setPermissionIds(arr);
        }
        if (sfsIds != null && !sfsIds.isEmpty()) {
            ArrayNode arr = JSON.arrayNode();
            sfsIds.forEach(arr::add);
            rn.setSensitiveFieldSetIds(arr);
        }
        if (scopeFixtures != null && !scopeFixtures.isEmpty()) {
            ArrayNode arr = JSON.arrayNode();
            for (ScopeRuleFixture s : scopeFixtures) {
                ObjectNode obj = JSON.objectNode();
                obj.put("scopeType", s.type.name());
                if (s.expr != null) obj.set("scopeExpr", s.expr);
                arr.add(obj);
            }
            rn.setDataScopes(arr);
        }
        grants.add(rn);

        // Split-table mirror — post-refactor these are the enricher's source
        // of truth for scope/SFS. model is resolved from the seeded nav.
        String model = modelForNav(navId);
        if (scopeFixtures != null && !scopeFixtures.isEmpty() && model != null) {
            ArrayNode arr = JSON.arrayNode();
            for (ScopeRuleFixture s : scopeFixtures) {
                ObjectNode obj = JSON.objectNode();
                obj.put("scopeType", s.type.name());
                if (s.expr != null) obj.set("scopeExpr", s.expr);
                arr.add(obj);
            }
            RoleDataScope rds = new RoleDataScope();
            rds.setId((long) (dataScopeGrants.size() + 1));
            rds.setRoleId(roleId);
            rds.setModel(model);
            rds.setDataScopes(arr);
            dataScopeGrants.add(rds);
        }
        if (sfsIds != null) {
            for (String sid : sfsIds) {
                RoleSensitiveFieldSet g = new RoleSensitiveFieldSet();
                g.setId((long) (sfsGrants.size() + 1));
                g.setRoleId(roleId);
                g.setSensitiveFieldSetId(sid);
                sfsGrants.add(g);
            }
        }
        return rn;
    }

    /** Look up the primary model of a seeded navigation (nav must be added
     *  before the grant that references it). */
    private String modelForNav(String navId) {
        for (Navigation n : navigations) {
            if (n.getId() != null && n.getId().equals(navId)) return n.getModel();
        }
        return null;
    }

    public UserRoleRel bindUserToRole(Long relId, Long userId, Long roleId) {
        UserRoleRel r = new UserRoleRel();
        r.setId(relId);
        r.setUserId(userId);
        r.setRoleId(roleId);
        userRoleRels.add(r);
        return r;
    }

    public SensitiveFieldSet sfs(String id, String model, List<String> fieldCodes) {
        SensitiveFieldSet s = new SensitiveFieldSet();
        s.setId(id);
        s.setModel(model);
        ArrayNode arr = JSON.arrayNode();
        fieldCodes.forEach(arr::add);
        s.setFieldCodes(arr);
        sensitiveFieldSets.add(s);
        return s;
    }

    // ─── scope helper ───

    public static class ScopeRuleFixture {
        public final ScopeType type;
        public final tools.jackson.databind.JsonNode expr;
        public ScopeRuleFixture(ScopeType type, tools.jackson.databind.JsonNode expr) {
            this.type = type;
            this.expr = expr;
        }
        public static ScopeRuleFixture all() {
            return new ScopeRuleFixture(ScopeType.ALL, null);
        }
        public static ScopeRuleFixture custom(String... tuple) {
            // tuple like: "userId", "=", "$principal.userId"
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
        roleService = mock(RoleService.class);
        userRoleRelService = mock(UserRoleRelService.class);
        roleNavigationService = mock(RoleNavigationService.class);
        roleDataScopeService = mock(RoleDataScopeService.class);
        roleSensitiveFieldSetService = mock(RoleSensitiveFieldSetService.class);

        // ModelService-level seeds — used by NavigationModelResolverImpl,
        // SensitiveFieldSetCache, EndpointIndex.
        when(modelService.searchList(eq("Navigation"), any(FlexQuery.class), eq(Navigation.class)))
                .thenReturn(navigations);
        when(modelService.searchList(eq("SensitiveFieldSet"), any(FlexQuery.class), eq(SensitiveFieldSet.class)))
                .thenReturn(sensitiveFieldSets);
        when(modelService.searchList(eq("Permission"), any(FlexQuery.class), eq(Permission.class)))
                .thenReturn(permissions);

        // Service-level searches — the enricher's read paths.
        when(userRoleRelService.searchList(any(FlexQuery.class))).thenAnswer(inv -> {
            FlexQuery q = inv.getArgument(0);
            Long userId = extractEqLongFromFlex(q, "userId");
            List<UserRoleRel> out = new ArrayList<>();
            for (UserRoleRel r : userRoleRels) {
                if (userId == null || userId.equals(r.getUserId())) out.add(r);
            }
            return out;
        });
        when(roleService.searchList(any(FlexQuery.class))).thenAnswer(inv -> {
            FlexQuery q = inv.getArgument(0);
            Set<Long> roleIds = extractInLongSetFromFlex(q, "id");
            List<Role> out = new ArrayList<>();
            for (Role r : roles) {
                if (!r.getActive()) continue;
                if (roleIds == null || roleIds.contains(r.getId())) out.add(r);
            }
            return out;
        });
        when(roleNavigationService.searchList(any(FlexQuery.class))).thenAnswer(inv -> {
            FlexQuery q = inv.getArgument(0);
            Set<Long> roleIds = extractInLongSetFromFlex(q, "roleId");
            List<RoleNavigation> out = new ArrayList<>();
            for (RoleNavigation rn : grants) {
                if (roleIds == null || roleIds.contains(rn.getRoleId())) out.add(rn);
            }
            return out;
        });
        when(roleDataScopeService.searchList(any(FlexQuery.class))).thenAnswer(inv -> {
            Set<Long> roleIds = extractInLongSetFromFlex(inv.getArgument(0), "roleId");
            List<RoleDataScope> out = new ArrayList<>();
            for (RoleDataScope r : dataScopeGrants) {
                if (roleIds == null || roleIds.contains(r.getRoleId())) out.add(r);
            }
            return out;
        });
        when(roleSensitiveFieldSetService.searchList(any(FlexQuery.class))).thenAnswer(inv -> {
            Set<Long> roleIds = extractInLongSetFromFlex(inv.getArgument(0), "roleId");
            List<RoleSensitiveFieldSet> out = new ArrayList<>();
            for (RoleSensitiveFieldSet r : sfsGrants) {
                if (roleIds == null || roleIds.contains(r.getRoleId())) out.add(r);
            }
            return out;
        });

        // Cache: always MISS so we exercise the DB-load path.
        when(cacheService.get(any(String.class), eq(io.softa.starter.user.dto.PermissionInfo.class)))
                .thenReturn(null);

        // Bootstrap real beans.
        navResolver = new NavigationModelResolverImpl(modelService);
        ReflectionTestUtils.invokeMethod(navResolver, "init");

        sfsCache = new SensitiveFieldSetCache(modelService);
        sfsCache.reload();

        endpointIndex = new EndpointIndex(modelService, navResolver);
        ReflectionTestUtils.invokeMethod(endpointIndex, "init");

        // Scope wiring — default to the two universal contributors when the
        // caller hasn't supplied their own list.
        if (scopeContributors.isEmpty()) {
            scopeContributors.add(new CreatedBySelfScopeContributor());
            scopeContributors.add(new CustomScopeContributor());
        }
        applicability = new ScopeApplicabilityResolver(scopeContributors);
        scopeCompiler = new ScopeRuleCompiler(applicability, scopeContributors);

        enricher = new PermissionInfoEnricher(
                roleService, userRoleRelService, roleNavigationService,
                roleDataScopeService, roleSensitiveFieldSetService,
                navResolver, sfsCache, cacheService);
        ReflectionTestUtils.invokeMethod(enricher, "initAncestorIndex");
    }

    /** Extract the RHS of a `field = ?` leaf from a FlexQuery's filters. */
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

    /** Extract the RHS values of a `field IN (...)` leaf as a Long set. */
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

    /** Silence static analyzer complaints — the raw ModelService typing is
     *  intentional (framework generic wildcard). */
    @SuppressWarnings("unused")
    private static NavigationModelResolver navResolverType() { return null; }
}
