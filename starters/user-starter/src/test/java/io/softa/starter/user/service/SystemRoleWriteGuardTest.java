package io.softa.starter.user.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;

/**
 * A built-in role (one carrying a {@code code}) is immutable through the write API, and its grants are
 * too — which is the half that was reachable before this guard: the grant tables are ordinary models
 * with their own generic CRUD, so nothing on the {@code Role} row stood in the way.
 *
 * <p>Cases are written against the aspect's advice rather than through a proxy, because what needs
 * pinning is the argument-shape reasoning: which of the dozen {@code ModelService} write signatures
 * names its rows in the payload, which by id, which by filter, and which by all three.
 */
class SystemRoleWriteGuardTest {

    private static final long BUILT_IN = 1L;      // code = EMPLOYEE
    private static final long ORDINARY = 2L;      // code = null (admin-created)

    private ModelService<?> modelService;
    private SystemRoleWriteGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelService = mock(ModelService.class);
        guard = new SystemRoleWriteGuard(modelService, mock(org.springframework.context.ApplicationEventPublisher.class));

        // Role lookups: 1 is built-in, 2 is not. Filtered by whichever ids the guard asks about, so a
        // case that resolves the wrong rows fails instead of quietly passing.
        when(modelService.searchList(eq("Role"), any(FlexQuery.class)))
                .thenAnswer(inv -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    if (askedAbout(inv.getArgument(1), BUILT_IN)) {
                        out.add(role(BUILT_IN, "EMPLOYEE", "Employee"));
                    }
                    if (askedAbout(inv.getArgument(1), ORDINARY)) {
                        out.add(role(ORDINARY, null, "Payroll Clerk"));
                    }
                    return out;
                });
    }

    // ── outside the wizard: the generic writes are for administrators ─────────────────────────────

    private static io.softa.framework.base.context.Context caller(String... roleCodes) {
        io.softa.framework.base.context.Context ctx = new io.softa.framework.base.context.Context();
        ctx.setTenantId(9L);
        ctx.setUserId(7L);
        ctx.setRoleCodes(java.util.Set.of(roleCodes));
        return ctx;
    }

    @Test
    void refusesAGenericGrantWriteFromANonAdministrator() {
        stubRows("RoleDataScope");

        assertThatThrownBy(() -> ContextHolder.runWith(caller(),
                () -> guard.guardCreate("RoleDataScope", List.of(row("roleId", ORDINARY)))))
                .isInstanceOf(io.softa.framework.base.exception.PermissionException.class)
                .hasMessageContaining("role wizard");
        assertThatThrownBy(() -> ContextHolder.runWith(caller(),
                () -> guard.guardUpdate("Role", List.of(row("id", ORDINARY, "dynamicFilter", "[]")))))
                .isInstanceOf(io.softa.framework.base.exception.PermissionException.class);
    }

    @Test
    void letsAnAdministratorWriteAnOrdinaryRolesGrants() {
        stubRows("RoleDataScope");

        assertThatCode(() -> ContextHolder.runWith(caller("TENANT_ADMIN"),
                () -> guard.guardCreate("RoleDataScope", List.of(row("roleId", ORDINARY)))))
                .doesNotThrowAnyException();
    }

    // ── the grants: the surface that had nothing guarding it ──────────────────────────────────────

    @Test
    void refusesANewGrantOnABuiltInRole() {
        stubRows("RoleNavigation");

        assertThatThrownBy(() -> guard.guardCreate("RoleNavigation", List.of(row("roleId", BUILT_IN))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Employee")
                .hasMessageContaining("EMPLOYEE");
    }

    @Test
    void refusesWideningTheRowScopeOfABuiltInRole() {
        // The sharpest case, and the reason this exists: one updateOne flipping EMPLOYEE's scope on the
        // Employee model from SELF to ALL makes every employee in the tenant readable by every other.
        // The payload names only the grant row's id, so the role is only visible by reading it back.
        stubRows("RoleDataScope", row("roleId", BUILT_IN));

        assertThatThrownBy(() -> guard.guardUpdate("RoleDataScope", List.of(row("id", 99L, "dataScopes", "[{\"scopeType\":\"ALL\"}]"))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void refusesDraggingAGrantOntoABuiltInRole() {
        // Stored row belongs to an ordinary role; the payload re-points it. Checking only the stored
        // side would let this through.
        stubRows("RoleSensitiveFieldSet", row("roleId", ORDINARY));

        assertThatThrownBy(() -> guard.guardUpdate("RoleSensitiveFieldSet", List.of(row("id", 99L, "roleId", BUILT_IN))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void refusesStrippingABuiltInRolesGrants() {
        stubRows("RoleNavigation", row("roleId", BUILT_IN));

        assertThatThrownBy(() -> guard.guardByIds("RoleNavigation", List.of(7L, 8L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void refusesADeleteWhoseStoredRowBelongsToABuiltInRole() {
        // The payload names only row ids; which role they belong to is read back before the delete.
        stubRows("RoleDataScope", row("roleId", BUILT_IN));

        assertThatThrownBy(() -> guard.guardByIds("RoleDataScope", List.of(11L, 12L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void readsTheRowsAnUpdateByFilterSelects() {
        // Three-argument signature: the rows come from the filter, the new values from the third arg.
        stubRows("RoleNavigation", row("roleId", BUILT_IN));

        assertThatThrownBy(() -> guard.guardUpdateByFilter("RoleNavigation",
                new Filters().eq("navigationId", "navigation.payroll"), row("permissionIds", "[]")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void letsAnOrdinaryRolesGrantsThrough() {
        stubRows("RoleNavigation", row("roleId", ORDINARY));

        assertThatCode(() -> guard.guardUpdate("RoleNavigation", List.of(row("id", 99L))))
                .doesNotThrowAnyException();
    }

    // ── the Role row itself ───────────────────────────────────────────────────────────────────────

    @Test
    void refusesEditingTheBuiltInRoleRow() {
        assertThatThrownBy(() -> guard.guardUpdate("Role", List.of(row("id", BUILT_IN, "name", "Renamed"))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void refusesMintingARoleThatClaimsACode() {
        // Otherwise the guard is self-defeating: anyone who can name their own code can declare a role
        // untouchable, and the generic /Role/createOne skips RoleController's own check.
        assertThatThrownBy(() -> guard.guardCreate("Role", List.of(row("name", "Mine", "code", "EMPLOYEE"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void letsAnAdminCreatedRoleThrough() {
        assertThatCode(() -> guard.guardCreate("Role", List.of(row("name", "Payroll Clerk"))))
                .doesNotThrowAnyException();
    }

    // ── what must keep working ────────────────────────────────────────────────────────────────────

    @Test
    void leavesMembershipAlone() {
        // Assigning users to a built-in role is the one change that stays open, so UserRoleRel must not
        // even be looked at.
        assertThatCode(() -> guard.guardCreate("UserRoleRel",
                List.of(row("roleId", BUILT_IN, "userId", 5L)))).doesNotThrowAnyException();
        verify(modelService, never()).searchList(eq("Role"), any(FlexQuery.class));
    }

    @Test
    void ignoresModelsItDoesNotGuard() {
        assertThatCode(() -> guard.guardUpdate("Employee", List.of(row("id", 3L))))
                .doesNotThrowAnyException();
        verify(modelService, never()).searchList(eq("Role"), any(FlexQuery.class));
    }

    @Test
    void letsSeedingAndSystemMaintenanceWrite() {
        // Pre-data loading and the entitlement downgrade cleanup both run permission-skipped, and both
        // legitimately write a built-in role's grants. A user request never carries this flag.
        Context system = new Context();
        system.setSkipPermissionCheck(true);

        assertThatCode(() -> ContextHolder.runWith(system,
                () -> guard.guardCreate("RoleNavigation", List.of(row("roleId", BUILT_IN)))))
                .doesNotThrowAnyException();
        verify(modelService, never()).searchList(eq("Role"), any(FlexQuery.class));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    /** Stub the guard's read-back of a grant model's rows. */
    @SafeVarargs
    private void stubRows(String model, Map<String, Object>... rows) {
        when(modelService.searchList(eq(model), any(FlexQuery.class))).thenReturn(List.of(rows));
    }

    private static boolean askedAbout(FlexQuery query, long roleId) {
        return query != null && query.getFilters() != null
                && query.getFilters().toString().contains(String.valueOf(roleId));
    }

    private static Map<String, Object> role(long id, String code, String name) {
        return row("id", id, "code", code, "name", name);
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            row.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return row;
    }


}
