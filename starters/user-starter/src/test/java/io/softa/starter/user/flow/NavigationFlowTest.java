package io.softa.starter.user.flow;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;

import io.softa.starter.user.dto.PermissionInfo;
import io.softa.starter.user.enums.NavigationType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end navigation visibility flow: seed nav tree → grant leaf →
 * enricher returns leaf + ancestors + granted permissions.
 */
class NavigationFlowTest {

    @BeforeEach
    void resetRequestScope() {
        // Prevent request-scoped PermissionInfo stashed by earlier tests
        // (e.g. PermissionInfoEnricherTest) from short-circuiting our
        // enrich() calls with stale data.
    RequestContextHolder.resetRequestAttributes();
    }

    private static PermissionFlowFixture threeLevelTree() {
        PermissionFlowFixture fx = new PermissionFlowFixture();
        // hr → hr.employee-mgmt → hr.employee-mgmt.employees
        fx.nav("hr", NavigationType.GROUP, null, null);
        fx.nav("hr.employee-mgmt", NavigationType.MENU, "Employee", "hr");
        fx.nav("hr.employee-mgmt.employees", NavigationType.MENU, "Employee", "hr.employee-mgmt");
        fx.perm("employee.view", "hr.employee-mgmt.employees", "POST /Employee/searchList");
        fx.perm("employee.create", "hr.employee-mgmt.employees", "POST /Employee/createOne");

        fx.role(100L, "HR Manager", null, true);
        fx.bindUserToRole(1L, 42L, 100L);
        return fx;
    }

    // ─── ancestor expansion: granting a leaf surfaces the whole chain ───

    @Test
    void leafGrant_expandsAncestorsInNavigationSet() {
        PermissionFlowFixture fx = threeLevelTree();
        fx.grant(1L, 100L, "hr.employee-mgmt.employees",
                Set.of("employee.view"), null, null);
        fx.wire();

        PermissionInfo pi = fx.enricher.enrich(10L, 42L);
        assertThat(pi.getNavigations())
                .containsExactlyInAnyOrder(
                        "hr.employee-mgmt.employees",
                        "hr.employee-mgmt",
                        "hr");
    }

    // ─── permissions aggregate across grants on the same nav ───

    @Test
    void multiplePermissionsGranted_allSurface() {
        PermissionFlowFixture fx = threeLevelTree();
        fx.grant(1L, 100L, "hr.employee-mgmt.employees",
                Set.of("employee.view", "employee.create"), null, null);
        fx.wire();

        PermissionInfo pi = fx.enricher.enrich(10L, 42L);
        assertThat(pi.getPermissions())
                .containsExactlyInAnyOrder("employee.view", "employee.create");
    }

    // ─── user with no grants → empty navigation and permission sets ───

    @Test
    void userWithoutGrants_hasEmptyNavigations() {
        PermissionFlowFixture fx = threeLevelTree();
        // Note: no fx.grant(...) call — role has no role_navigation rows.
        fx.wire();

        PermissionInfo pi = fx.enricher.enrich(10L, 42L);
        assertThat(pi.getNavigations()).isNullOrEmpty();
        assertThat(pi.getPermissions()).isNullOrEmpty();
    }

    // ─── multiple grants on different nav rows → both leaves + their ancestors ───

    @Test
    void grantsOnSiblingLeaves_expandsBothChains() {
        PermissionFlowFixture fx = new PermissionFlowFixture();
        fx.nav("hr", NavigationType.GROUP, null, null);
        fx.nav("hr.employee", NavigationType.MENU, "Employee", "hr");
        fx.nav("hr.department", NavigationType.MENU, "Department", "hr");
        fx.perm("employee.view", "hr.employee", "POST /Employee/searchList");
        fx.perm("department.view", "hr.department", "POST /Department/searchList");

        fx.role(100L, "HR Manager", null, true);
        fx.grant(1L, 100L, "hr.employee", Set.of("employee.view"), null, null);
        fx.grant(2L, 100L, "hr.department", Set.of("department.view"), null, null);
        fx.bindUserToRole(1L, 42L, 100L);
        fx.wire();

        PermissionInfo pi = fx.enricher.enrich(10L, 42L);
        assertThat(pi.getNavigations())
                .containsExactlyInAnyOrder("hr", "hr.employee", "hr.department");
        assertThat(pi.getPermissions())
                .containsExactlyInAnyOrder("employee.view", "department.view");
    }

    // ─── super-admin → empty-grants snapshot (nav/perm sets null / empty) ───

    @Test
    void superAdmin_emptyGrantsSnapshot() {
        PermissionFlowFixture fx = new PermissionFlowFixture();
        fx.nav("hr", NavigationType.GROUP, null, null);
        fx.role(1L, "Super Admin", "SUPER_ADMIN", true);
        fx.bindUserToRole(1L, 1L, 1L);
        fx.wire();

        PermissionInfo pi = fx.enricher.enrich(10L, 1L);
        // The short-circuit builds an empty-grants snapshot; roleCodes carries
        // SUPER_ADMIN so downstream layers detect the bypass.
        assertThat(pi.getRoleCodes()).containsExactly("SUPER_ADMIN");
        assertThat(pi.isSuperAdmin()).isTrue();
        assertThat(pi.getPermissions()).isNullOrEmpty();
        assertThat(pi.getNavigations()).isNullOrEmpty();
    }

    // ─── active=false roles → their grants don't appear ───

    @Test
    void inactiveRole_grantsDropped() {
        PermissionFlowFixture fx = threeLevelTree();
        // Mark the role inactive — loadActiveRolesFor filters it out.
        fx.roles.getFirst().setActive(false);
        fx.grant(1L, 100L, "hr.employee-mgmt.employees",
                Set.of("employee.view"), null, null);
        fx.wire();

        PermissionInfo pi = fx.enricher.enrich(10L, 42L);
        assertThat(pi.getNavigations()).isNullOrEmpty();
        assertThat(pi.getPermissions()).isNullOrEmpty();
    }

    // ─── two roles, one active one inactive → only active grants surface ───

    @Test
    void mixedActiveRoles_onlyActiveGrantsSurface() {
        PermissionFlowFixture fx = new PermissionFlowFixture();
        fx.nav("hr", NavigationType.GROUP, null, null);
        fx.nav("hr.employee", NavigationType.MENU, "Employee", "hr");
        fx.nav("hr.department", NavigationType.MENU, "Department", "hr");
        fx.perm("employee.view", "hr.employee", "POST /Employee/searchList");
        fx.perm("department.view", "hr.department", "POST /Department/searchList");

        fx.role(100L, "HR", null, true);           // active
        fx.role(200L, "Finance", null, false);     // inactive
        fx.grant(1L, 100L, "hr.employee", Set.of("employee.view"), null, null);
        fx.grant(2L, 200L, "hr.department", Set.of("department.view"), null, null);
        fx.bindUserToRole(1L, 42L, 100L);
        fx.bindUserToRole(2L, 42L, 200L);
        fx.wire();

        PermissionInfo pi = fx.enricher.enrich(10L, 42L);
        // Only the active role's grants surface.
        assertThat(pi.getPermissions()).containsExactly("employee.view");
        assertThat(pi.getNavigations()).containsExactlyInAnyOrder("hr", "hr.employee");
    }
}
