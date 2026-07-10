package io.softa.starter.user.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.dto.PermissionInfo;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.entity.Navigation;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.RoleDataScope;
import io.softa.starter.user.entity.RoleNavigation;
import io.softa.starter.user.entity.RoleSensitiveFieldSet;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.NavigationType;
import io.softa.starter.user.enums.ScopeType;
import io.softa.starter.user.filter.SensitiveFieldSetCache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Composition-focused tests for {@link PermissionInfoEnricher#enrich}'s DB
 * load path — the surrounding cache-tier tests live in
 * {@link PermissionInfoEnricherTest}.
 */
class PermissionInfoEnricherCompositionTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private RoleService roleService;
    private UserRoleRelService userRoleRelService;
    private RoleNavigationService roleNavigationService;
    private RoleDataScopeService roleDataScopeService;
    private RoleSensitiveFieldSetService roleSensitiveFieldSetService;
    private NavigationModelResolver navResolver;
    private SensitiveFieldSetCache sfsCache;
    private CacheService cacheService;

    private final List<RoleDataScope> dataScopeGrants = new java.util.ArrayList<>();
    private final List<RoleSensitiveFieldSet> sfsGrants = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        roleService = mock(RoleService.class);
        userRoleRelService = mock(UserRoleRelService.class);
        roleNavigationService = mock(RoleNavigationService.class);
        roleDataScopeService = mock(RoleDataScopeService.class);
        roleSensitiveFieldSetService = mock(RoleSensitiveFieldSetService.class);
        navResolver = mock(NavigationModelResolver.class);
        sfsCache = mock(SensitiveFieldSetCache.class);
        cacheService = mock(CacheService.class);
        when(cacheService.get(anyString(), eq(PermissionInfo.class))).thenReturn(null);
        // Defaults — scope/SFS now come from their own services; return the
        // per-test lists (mutated by the dataScope()/sfsGrant() helpers).
        when(roleNavigationService.searchList(any(FlexQuery.class))).thenReturn(List.of());
        when(roleDataScopeService.searchList(any(FlexQuery.class))).thenReturn(dataScopeGrants);
        when(roleSensitiveFieldSetService.searchList(any(FlexQuery.class))).thenReturn(sfsGrants);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private PermissionInfoEnricher enricher() {
        PermissionInfoEnricher e = new PermissionInfoEnricher(
                roleService, userRoleRelService, roleNavigationService,
                roleDataScopeService, roleSensitiveFieldSetService,
                navResolver, sfsCache, cacheService);
        ReflectionTestUtils.invokeMethod(e, "initAncestorIndex");
        return e;
    }

    private void primeUserWithRoles(Long userId, Long... roleIds) {
        List<UserRoleRel> rels = new java.util.ArrayList<>();
        for (Long rid : roleIds) {
            UserRoleRel r = new UserRoleRel();
            r.setUserId(userId);
            r.setRoleId(rid);
            rels.add(r);
        }
        when(userRoleRelService.searchList(any(FlexQuery.class))).thenReturn(rels);

        List<Role> roles = new java.util.ArrayList<>();
        for (Long rid : roleIds) {
            Role role = new Role();
            role.setId(rid);
            role.setActive(true);
            roles.add(role);
        }
        when(roleService.searchList(any(FlexQuery.class))).thenReturn(roles);
    }

    private static RoleNavigation grant(Long roleId, String navId, List<ScopeRule> scopes, List<String> sfsIds, List<String> permIds) {
        RoleNavigation rn = new RoleNavigation();
        rn.setRoleId(roleId);
        rn.setNavigationId(navId);
        if (permIds != null) {
            ArrayNode arr = JSON.arrayNode();
            permIds.forEach(arr::add);
            rn.setPermissionIds(arr);
        }
        if (sfsIds != null) {
            ArrayNode arr = JSON.arrayNode();
            sfsIds.forEach(arr::add);
            rn.setSensitiveFieldSetIds(arr);
        }
        if (scopes != null) {
            ArrayNode arr = JSON.arrayNode();
            for (ScopeRule s : scopes) {
                ObjectNode obj = JSON.objectNode();
                obj.put("scopeType", s.getScopeType().name());
                if (s.getScopeExpr() != null) obj.set("scopeExpr", s.getScopeExpr());
                arr.add(obj);
            }
            rn.setDataScopes(arr);
        }
        return rn;
    }

    private static ScopeRule scope(ScopeType type) {
        return new ScopeRule(type, null);
    }

    /** Seed a role_data_scope row (post-refactor scope source). */
    private RoleDataScope dataScope(Long roleId, String model, ScopeType... types) {
        ArrayNode arr = JSON.arrayNode();
        for (ScopeType t : types) {
            ObjectNode obj = JSON.objectNode();
            obj.put("scopeType", t.name());
            arr.add(obj);
        }
        RoleDataScope r = new RoleDataScope();
        r.setRoleId(roleId);
        r.setModel(model);
        r.setDataScopes(arr);
        dataScopeGrants.add(r);
        return r;
    }

    /** Seed role_sensitive_field_set rows (post-refactor SFS source). */
    private void sfsGrant(Long roleId, String... sids) {
        for (String sid : sids) {
            RoleSensitiveFieldSet r = new RoleSensitiveFieldSet();
            r.setRoleId(roleId);
            r.setSensitiveFieldSetId(sid);
            sfsGrants.add(r);
        }
    }

    // ─── modelScopeMap grouping ───

    @Test
    void modelScopeMap_twoGrantsOnSameModel_orCombined() {
        primeUserWithRoles(42L, 100L);
        dataScope(100L, "Employee", ScopeType.SELF);
        dataScope(100L, "Employee", ScopeType.CREATED_BY_SELF);

        PermissionInfo pi = enricher().enrich(10L, 42L);
        Map<String, List<ScopeRule>> map = pi.getModelScopeMap();
        assertThat(map).containsOnlyKeys("Employee");
        assertThat(map.get("Employee")).hasSize(2)
                .extracting(ScopeRule::getScopeType)
                .containsExactlyInAnyOrder(ScopeType.SELF, ScopeType.CREATED_BY_SELF);
    }

    @Test
    void modelScopeMap_grantsAcrossDifferentModels_areKeyedSeparately() {
        primeUserWithRoles(42L, 100L);
        dataScope(100L, "Employee", ScopeType.SELF);
        dataScope(100L, "LeaveRequest", ScopeType.CREATED_BY_SELF);

        Map<String, List<ScopeRule>> map = enricher().enrich(10L, 42L).getModelScopeMap();
        assertThat(map.keySet()).containsExactlyInAnyOrder("Employee", "LeaveRequest");
        assertThat(map.get("Employee")).extracting(ScopeRule::getScopeType).containsExactly(ScopeType.SELF);
        assertThat(map.get("LeaveRequest")).extracting(ScopeRule::getScopeType).containsExactly(ScopeType.CREATED_BY_SELF);
    }

    @Test
    void modelScopeMap_rowWithBlankModel_skipped() {
        // A role_data_scope row with a null/blank model can't populate
        // modelScopeMap — the enricher skips it defensively.
        primeUserWithRoles(42L, 100L);
        dataScope(100L, "", ScopeType.ALL);

        PermissionInfo pi = enricher().enrich(10L, 42L);
        assertThat(pi.getModelScopeMap()).isEmpty();
    }

    // ─── modelSensitiveFieldSetsMap canonical-model routing ───

    @Test
    void sfsMap_keyedByCanonicalModel_notByGrantingNavModel() {
        // Grant is on hr.employee (Employee nav), but the SFS "bank" is
        // bound to EmpBankAccount. Result must key by "EmpBankAccount".
        primeUserWithRoles(42L, 100L);
        when(sfsCache.modelOf("comp")).thenReturn("Employee");
        when(sfsCache.modelOf("bank")).thenReturn("EmpBankAccount");
        sfsGrant(100L, "comp", "bank");

        Map<String, Set<String>> map = enricher().enrich(10L, 42L)
                .getModelSensitiveFieldSetsMap();
        assertThat(map.get("Employee")).containsExactly("comp");
        assertThat(map.get("EmpBankAccount")).containsExactly("bank");
    }

    @Test
    void sfsMap_unknownSetIdSkippedSilently() {
        // SFS row removed but grant still references it — enricher must not throw.
        primeUserWithRoles(42L, 100L);
        when(sfsCache.modelOf("comp")).thenReturn("Employee");
        when(sfsCache.modelOf("ghost")).thenReturn(null);
        sfsGrant(100L, "comp", "ghost");

        Map<String, Set<String>> map = enricher().enrich(10L, 42L)
                .getModelSensitiveFieldSetsMap();
        assertThat(map.get("Employee")).containsExactly("comp");
        assertThat(map).doesNotContainKey("ghost");
    }

    // ─── permissions union ───

    @Test
    void permissions_unionedAcrossGrants() {
        primeUserWithRoles(42L, 100L, 200L);
        when(navResolver.resolvePrimaryModel(anyString())).thenReturn("Employee");
        when(roleNavigationService.searchList(any(FlexQuery.class))).thenReturn(List.of(
                grant(100L, "hr.employee", null, null, List.of("employee.view")),
                grant(200L, "hr.employee", null, null, List.of("employee.create", "employee.view"))));

        assertThat(enricher().enrich(10L, 42L).getPermissions())
                .containsExactlyInAnyOrder("employee.view", "employee.create");
    }

    // ─── ancestor expansion edge cases ───

    @Test
    void ancestorExpansion_cycle_terminatesWithVisitedGuard() {
        // Simulate a→b→a cycle. initAncestorIndex has depth cap 32 + visited
        // set — must not infinite-loop.
        Navigation a = navigation("a", "b");
        Navigation b = navigation("b", "a");
        when(navResolver.allNavigations()).thenReturn(List.of(a, b));

        primeUserWithRoles(42L, 100L);
        when(navResolver.resolvePrimaryModel("a")).thenReturn("A");
        when(roleNavigationService.searchList(any(FlexQuery.class))).thenReturn(List.of(
                grant(100L, "a", null, null, null)));

        // Should complete without hanging or throwing.
        PermissionInfo pi = enricher().enrich(10L, 42L);
        assertThat(pi.getNavigations()).contains("a");
    }

    @Test
    void ancestorExpansion_depthCapHonored() {
        // Chain of 40 navs — depth cap of 32 should stop the walk cleanly.
        List<Navigation> chain = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            chain.add(navigation("n" + i, i == 0 ? null : "n" + (i - 1)));
        }
        when(navResolver.allNavigations()).thenReturn(chain);

        primeUserWithRoles(42L, 100L);
        when(navResolver.resolvePrimaryModel("n39")).thenReturn("X");
        when(roleNavigationService.searchList(any(FlexQuery.class))).thenReturn(List.of(
                grant(100L, "n39", null, null, null)));

        PermissionInfo pi = enricher().enrich(10L, 42L);
        // Depth cap = 32 ancestors (walk breaks after 32 hops) + leaf itself.
        assertThat(pi.getNavigations()).contains("n39");
        // The very top of the chain (n0) is beyond the depth cap → excluded.
        assertThat(pi.getNavigations()).doesNotContain("n0");
        // Bounded by depth cap + leaf ≤ 34; well below the 40-node total.
        assertThat(pi.getNavigations().size()).isLessThan(40);
    }

    // ─── active-only role filter ───

    @Test
    void inactiveRoles_grantsSkippedByLoadActiveRolesFor() {
        // Bind user to two roles, one inactive.
        UserRoleRel r1 = new UserRoleRel();
        r1.setUserId(42L);
        r1.setRoleId(100L);
        UserRoleRel r2 = new UserRoleRel();
        r2.setUserId(42L);
        r2.setRoleId(200L);
        when(userRoleRelService.searchList(any(FlexQuery.class))).thenReturn(List.of(r1, r2));

        Role active = new Role();
        active.setId(100L);
        active.setActive(true);
        Role inactive = new Role();
        inactive.setId(200L);
        inactive.setActive(false);
        // roleService.searchList uses `active=true` filter — return only the active row.
        when(roleService.searchList(any(FlexQuery.class))).thenReturn(List.of(active));

        when(navResolver.resolvePrimaryModel("hr.employee")).thenReturn("Employee");
        when(roleNavigationService.searchList(any(FlexQuery.class))).thenReturn(List.of(
                grant(100L, "hr.employee", null, null, List.of("employee.view"))));

        PermissionInfo pi = enricher().enrich(10L, 42L);
        // Only role 100's grants surface.
        assertThat(pi.getPermissions()).containsExactly("employee.view");
    }

    // ─── empty snapshot when user has no active roles ───

    @Test
    void noActiveRoles_returnsEmptyGrantsSnapshot() {
        when(userRoleRelService.searchList(any(FlexQuery.class))).thenReturn(List.of());
        when(roleService.searchList(any(FlexQuery.class))).thenReturn(List.of());

        PermissionInfo pi = enricher().enrich(10L, 42L);
        assertThat(pi.getPermissions()).isNullOrEmpty();
        assertThat(pi.getNavigations()).isNullOrEmpty();
        assertThat(pi.getModelScopeMap()).isNullOrEmpty();
    }

    // ─── helpers ───

    private static Navigation navigation(String id, String parentId) {
        Navigation n = new Navigation();
        n.setId(id);
        n.setParentId(parentId);
        n.setType(NavigationType.MENU);
        return n;
    }
}
