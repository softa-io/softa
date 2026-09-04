package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.Context;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.EntitlementService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.dto.EffectiveAccess;
import io.softa.starter.user.dto.UiContext;
import io.softa.starter.user.entity.Navigation;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.RoleNavigation;
import io.softa.starter.user.entity.RoleSensitiveFieldSet;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.NavigationType;
import io.softa.starter.user.service.NavigationModelResolver;
import io.softa.starter.user.service.RoleDataScopeService;
import io.softa.starter.user.service.RoleNavigationService;
import io.softa.starter.user.service.RoleSensitiveFieldSetService;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserRoleRelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the cache-miss fallback builder. Verifies it reproduces the
 * engine snapshot's FE-facing shape (roleCodes / navigations / permissions /
 * modelSensitiveFieldSetsMap) from user-starter's own entities,
 * including ancestor expansion and the SUPER_ADMIN / no-role empty-grants case.
 */
class UiContextBuilderTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private UserRoleRelService userRoleRelService;
    private RoleService roleService;
    private RoleNavigationService roleNavigationService;
    private RoleSensitiveFieldSetService roleSensitiveFieldSetService;
    private RoleDataScopeService roleDataScopeService;
    private NavigationModelResolver navigationModelResolver;
    private ModelService<?> modelService;
    private UiContextBuilder builder;

    @BeforeEach
    void setUp() {
        userRoleRelService = mock(UserRoleRelService.class);
        roleService = mock(RoleService.class);
        roleNavigationService = mock(RoleNavigationService.class);
        roleSensitiveFieldSetService = mock(RoleSensitiveFieldSetService.class);
        roleDataScopeService = mock(RoleDataScopeService.class);
        navigationModelResolver = mock(NavigationModelResolver.class);
        modelService = mock(ModelService.class);

        // Nav tree: hr → hr.employee (leaf). Ancestor index built from this.
        when(navigationModelResolver.allNavigations()).thenReturn(List.of(
                nav("hr", null, NavigationType.GROUP),
                nav("hr.employee", "hr", NavigationType.MENU)));

        builder = new UiContextBuilder(userRoleRelService, roleService, roleNavigationService,
                roleSensitiveFieldSetService, roleDataScopeService, navigationModelResolver, modelService);
        ReflectionTestUtils.invokeMethod(builder, "initAncestorIndex");
    }

    @Test
    void noRoles_emptyGrants_notSuperAdmin() {
        when(userRoleRelService.searchList(any(FlexQuery.class))).thenReturn(List.of());

        UiContext out = builder.build(42L);

        assertThat(out.getRoleCodes()).isEmpty();
        assertThat(out.getNavigations()).isEmpty();
        assertThat(out.getPermissions()).isEmpty();
    }

    @Test
    void superAdmin_emptyGrantsButFlagged() {
        when(userRoleRelService.searchList(any(FlexQuery.class))).thenReturn(List.of(rel(42L, 1L)));
        when(roleService.searchList(any(FlexQuery.class))).thenReturn(List.of(role(1L, "SUPER_ADMIN")));

        UiContext out = builder.build(42L);

        // Super-admin is carried by roleCodes (the FE derives it); grants stay empty.
        assertThat(out.getRoleCodes()).containsExactly("SUPER_ADMIN");
        assertThat(out.getNavigations()).isEmpty();
        assertThat(out.getPermissions()).isEmpty();
    }

    @Test
    void normalUser_navsWithAncestors_permissions_andSfsMap() {
        when(userRoleRelService.searchList(any(FlexQuery.class))).thenReturn(List.of(rel(42L, 100L)));
        when(roleService.searchList(any(FlexQuery.class))).thenReturn(List.of(role(100L, "HR")));
        when(roleNavigationService.searchList(any(FlexQuery.class)))
                .thenReturn(List.of(roleNav("hr.employee", "employee.view", "employee.create")));
        when(roleSensitiveFieldSetService.searchList(any(FlexQuery.class)))
                .thenReturn(List.of(roleSfs("comp")));
        when(modelService.searchList(eq("SensitiveFieldSet"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("id", "comp", "model", "Employee")));

        UiContext out = builder.build(42L);

        // Leaf grant expands to include the ancestor "hr".
        assertThat(out.getNavigations()).containsExactlyInAnyOrder("hr.employee", "hr");
        assertThat(out.getPermissions()).containsExactlyInAnyOrder("employee.view", "employee.create");
        // SFS grouped under its canonical model.
        assertThat(out.getModelSensitiveFieldSetsMap().get("Employee")).containsExactly("comp");
    }

    @Test
    void effectiveAccess_nonAdminRole_computedFalse() {
        EffectiveAccess access = builder.effectiveAccessForRole("HR", 1L);

        assertThat(access.isComputed()).isFalse();
        assertThat(access.getRoleCode()).isNull();
        assertThat(access.getNavigations()).isNull();
        assertThat(access.getPermissions()).isNull();
        assertThat(access.getDataScopeAll()).isNull();
        assertThat(access.getSensitiveAll()).isNull();
    }

    @Test
    void effectiveAccess_superAdmin_everyNavAndPermission() {
        // Default nav tree (hr, hr.employee); permissions mapped to those navs.
        when(modelService.searchList(eq("Permission"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("id", "employee.view", "navigationId", "hr.employee"),
                Map.of("id", "hr.open", "navigationId", "hr")));

        EffectiveAccess access = builder.effectiveAccessForRole("SUPER_ADMIN", 99L);

        assertThat(access.isComputed()).isTrue();
        assertThat(access.getRoleCode()).isEqualTo("SUPER_ADMIN");
        // SUPER_ADMIN keeps every nav (platform included) + those navs' permissions.
        assertThat(access.getNavigations()).containsExactlyInAnyOrder("hr", "hr.employee");
        assertThat(access.getPermissions()).containsExactlyInAnyOrder("employee.view", "hr.open");
        assertThat(access.getDataScopeAll()).isTrue();
        assertThat(access.getSensitiveAll()).isTrue();
    }

    @Test
    void effectiveAccess_tenantAdmin_dropsPlatformOnlyNavs() {
        ReflectionTestUtils.setField(builder, "platformNavPrefixesCsv", "navigation.system.");
        when(navigationModelResolver.allNavigations()).thenReturn(List.of(
                nav("core-hr.employee", null, NavigationType.MENU),
                nav("navigation.system.audit", null, NavigationType.MENU)));
        when(modelService.searchList(eq("Permission"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("id", "employee.view", "navigationId", "core-hr.employee"),
                Map.of("id", "audit.view", "navigationId", "navigation.system.audit")));

        EffectiveAccess access = builder.effectiveAccessForRole("TENANT_ADMIN", 7L);

        assertThat(access.isComputed()).isTrue();
        assertThat(access.getRoleCode()).isEqualTo("TENANT_ADMIN");
        // Platform-only nav (+ its permission) dropped; tenant-facing nav kept.
        assertThat(access.getNavigations()).containsExactly("core-hr.employee");
        assertThat(access.getPermissions()).containsExactly("employee.view");
        assertThat(access.getDataScopeAll()).isTrue();
        assertThat(access.getSensitiveAll()).isTrue();
    }

    @Test
    void effectiveAccess_tenantAdmin_narrowedByPlanEntitlement() {
        // Entitlement gate installed → only the "core-hr" module is entitled.
        EntitlementService gate = mock(EntitlementService.class);
        when(gate.entitledModules(7L)).thenReturn(Set.of("core-hr"));
        ReflectionTestUtils.setField(builder, "entitlementService", gate);
        when(navigationModelResolver.allNavigations()).thenReturn(List.of(
                nav("core-hr.employee", null, NavigationType.MENU),
                nav("ai.chat", null, NavigationType.MENU)));
        when(modelService.searchList(eq("Permission"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("id", "employee.view", "navigationId", "core-hr.employee"),
                Map.of("id", "ai.use", "navigationId", "ai.chat")));

        EffectiveAccess access = builder.effectiveAccessForRole("TENANT_ADMIN", 7L);

        // "ai" module not entitled → its nav + permission dropped; "core-hr" kept.
        assertThat(access.getNavigations()).containsExactly("core-hr.employee");
        assertThat(access.getPermissions()).containsExactly("employee.view");
    }

    // ─── build() for a tenant admin — the live uiContext, not the role-detail display ───
    //
    // The two paths above compute the same thing for the role-detail page, which is read-only. These
    // cover build(), which is what a logged-in admin's own uiContext comes from, and which had no plan
    // narrowing at all: the sidebar showed modules the tenant no longer pays for. Worth pinning
    // separately because nothing makes the two paths agree — they are hand-written twins.

    /** A tenant admin logged into tenant 7, with the two-module nav tree the plan cases below narrow. */
    private void tenantAdminOf(Long tenantId) {
        when(userRoleRelService.searchList(any(FlexQuery.class))).thenReturn(List.of(rel(42L, 1L)));
        when(roleService.searchList(any(FlexQuery.class))).thenReturn(List.of(role(1L, "TENANT_ADMIN")));
        when(navigationModelResolver.allNavigations()).thenReturn(List.of(
                nav("core-hr.employee", null, NavigationType.MENU),
                nav("payroll.pay-item", null, NavigationType.MENU)));
        when(modelService.searchList(eq("Permission"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("id", "employee.view", "navigationId", "core-hr.employee"),
                Map.of("id", "pay-item.view", "navigationId", "payroll.pay-item")));
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        ctx.setUserId(42L);
        this.ctx = ctx;
    }

    private Context ctx;

    private UiContext buildAsTenantAdmin() {
        return ContextHolder.callWith(ctx, () -> builder.build(42L));
    }

    @Test
    void build_tenantAdmin_narrowedByPlanEntitlement() {
        // The reported bug: after a Pro→Free downgrade, uiContext still listed navigation.payroll.
        // A tenant admin holds no static nav grants, so the downgrade cleanup had nothing to strip for
        // it — narrowing here is the only thing that can take the module away.
        tenantAdminOf(7L);
        EntitlementService gate = mock(EntitlementService.class);
        when(gate.entitledModules(7L)).thenReturn(Set.of("core-hr"));
        ReflectionTestUtils.setField(builder, "entitlementService", gate);

        UiContext out = buildAsTenantAdmin();

        assertThat(out.getNavigations()).containsExactly("core-hr.employee");
        assertThat(out.getPermissions()).containsExactly("employee.view");
    }

    @Test
    void build_tenantAdmin_resolvesTheEntitledSetOncePerBuild() {
        // The resolver is cache-aside; calling it per nav spends a Redis round-trip on each of a
        // hundred-odd navigations to re-read one unchanging set.
        tenantAdminOf(7L);
        EntitlementService gate = mock(EntitlementService.class);
        when(gate.entitledModules(7L)).thenReturn(Set.of("core-hr", "payroll"));
        ReflectionTestUtils.setField(builder, "entitlementService", gate);

        buildAsTenantAdmin();

        org.mockito.Mockito.verify(gate, org.mockito.Mockito.times(1)).entitledModules(7L);
    }

    @Test
    void build_tenantAdmin_noGateInstalled_keepsEveryModule() {
        // A pure-enforce deployment without tenant-starter has no gate. Narrowing to nothing there
        // would empty the sidebar of every admin on it.
        tenantAdminOf(7L);   // entitlementService left unset

        UiContext out = buildAsTenantAdmin();

        assertThat(out.getNavigations())
                .containsExactlyInAnyOrder("core-hr.employee", "payroll.pay-item");
    }

    // ─── build() for a consultant — the half that was missed ───
    //
    // The consultant principal was added to the permission engine first, where it made the endpoint
    // gate and the row scope work. This build is the OTHER assembly of the same question (see the
    // class doc's consistency caveat) and it reads role ROWS, of which a consultant has none — so it
    // fell straight through to empty grants. The consultant passed every permission check and was
    // shown a shell with no menus in it, which reads as bad seed data rather than a half-applied
    // change. These pin both halves of that: that the branch is reached at all, and that reaching it
    // needs an actual consultant.

    /** A consultant logged into tenant 7 — same nav tree as the admin cases, and NO role rows. */
    private void consultantOf(Long tenantId) {
        when(userRoleRelService.searchList(any(FlexQuery.class))).thenReturn(List.of());
        when(navigationModelResolver.allNavigations()).thenReturn(List.of(
                nav("core-hr.employee", null, NavigationType.MENU),
                nav("payroll.pay-item", null, NavigationType.MENU)));
        when(modelService.searchList(eq("Permission"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("id", "employee.view", "navigationId", "core-hr.employee"),
                Map.of("id", "pay-item.view", "navigationId", "payroll.pay-item")));
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("consultant", true)));
        Context c = new Context();
        c.setTenantId(tenantId);
        c.setUserId(42L);
        this.ctx = c;
    }

    @Test
    void build_consultant_getsTheTenantsMenus_notEmptyGrants() {
        consultantOf(7L);   // no entitlement gate → every module entitled

        UiContext out = ContextHolder.callWith(ctx, () -> builder.build(42L));

        assertThat(out.getNavigations())
                .containsExactlyInAnyOrder("core-hr.employee", "payroll.pay-item");
        assertThat(out.getPermissions())
                .containsExactlyInAnyOrder("employee.view", "pay-item.view");
        // Carried to the FE too: the top bar has to be able to say which hat the user is wearing.
        assertThat(out.getRoleCodes()).containsExactly("CONSULTANT");
    }

    @Test
    void build_consultant_narrowedByPlanEntitlement() {
        // A consultant's reach is defined as the tenant's CURRENT subscription, so a downgrade has to
        // narrow it here for the same reason it does for an admin: neither holds static nav grants,
        // so the downgrade cleanup has nothing to strip for either.
        consultantOf(7L);
        EntitlementService gate = mock(EntitlementService.class);
        when(gate.entitledModules(7L)).thenReturn(Set.of("core-hr"));
        ReflectionTestUtils.setField(builder, "entitlementService", gate);

        UiContext out = ContextHolder.callWith(ctx, () -> builder.build(42L));

        assertThat(out.getNavigations()).containsExactly("core-hr.employee");
        assertThat(out.getPermissions()).containsExactly("employee.view");
    }

    @Test
    void build_rolelessOrdinaryUser_stillGetsNothing() {
        // The other half of the pair. A derivation stuck on true would satisfy both tests above and
        // hand every roleless account in the deployment a tenant's entire menu.
        consultantOf(7L);
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("consultant", false)));

        UiContext out = ContextHolder.callWith(ctx, () -> builder.build(42L));

        assertThat(out.getRoleCodes()).isEmpty();
        assertThat(out.getNavigations()).isEmpty();
        assertThat(out.getPermissions()).isEmpty();
    }

    @Test
    void build_consultantFlagIsReadHoweverTheDriverMapsIt() {
        // The generic map read hands a MySQL TINYINT(1) back as an Integer, not a Boolean. Read only
        // as a Boolean, this answers false against a live database while every mock above stays green.
        consultantOf(7L);
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("consultant", 1)));

        UiContext out = ContextHolder.callWith(ctx, () -> builder.build(42L));

        assertThat(out.getRoleCodes()).containsExactly("CONSULTANT");
        assertThat(out.getNavigations()).isNotEmpty();
    }

    // ─── helpers ───

    private static Navigation nav(String id, String parentId, NavigationType type) {
        Navigation n = new Navigation();
        n.setId(id);
        n.setParentId(parentId);
        n.setType(type);
        return n;
    }

    private static UserRoleRel rel(Long userId, Long roleId) {
        UserRoleRel r = new UserRoleRel();
        r.setUserId(userId);
        r.setRoleId(roleId);
        return r;
    }

    private static Role role(Long id, String code) {
        Role r = new Role();
        r.setId(id);
        r.setCode(code);
        r.setActive(true);
        return r;
    }

    private static RoleNavigation roleNav(String navId, String... permIds) {
        RoleNavigation rn = new RoleNavigation();
        rn.setNavigationId(navId);
        ArrayNode arr = JSON.arrayNode();
        for (String p : permIds) arr.add(p);
        rn.setPermissionIds(arr);
        return rn;
    }

    private static RoleSensitiveFieldSet roleSfs(String sfsId) {
        RoleSensitiveFieldSet g = new RoleSensitiveFieldSet();
        g.setSensitiveFieldSetId(sfsId);
        return g;
    }
}
