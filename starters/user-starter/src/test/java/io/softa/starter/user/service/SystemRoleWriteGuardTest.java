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
 * What may be written to a role and its grants: a role's {@code code} never changes, a built-in role is
 * never deleted or copied, and the administrator roles accept no edit — while every other built-in role
 * is open to the same edits as one the tenant created. The grant tables are ordinary models with their
 * own generic CRUD, so the role behind a grant row has to be read back before the rules can apply.
 *
 * <p>Cases are written against the guard directly rather than through a proxy, because what needs
 * pinning is the argument-shape reasoning: which of the dozen {@code ModelService} write signatures
 * names its rows in the payload, which by id, which by filter, and which by all three.
 */
class SystemRoleWriteGuardTest {

    private static final long BUILT_IN = 1L;      // code = EMPLOYEE — a business built-in role
    private static final long ORDINARY = 2L;      // code = null (admin-created)
    private static final long ADMIN = 3L;         // code = TENANT_ADMIN — access computed at runtime

    private ModelService<?> modelService;
    private SystemRoleWriteGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelService = mock(ModelService.class);
        guard = new SystemRoleWriteGuard(modelService);

        // Role lookups: 1 is a business built-in, 2 is ordinary, 3 an administrator role. Filtered by whichever ids the guard asks about, so a
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
                    if (askedAbout(inv.getArgument(1), ADMIN)) {
                        out.add(role(ADMIN, "TENANT_ADMIN", "Tenant Admin"));
                    }
                    return out;
                });
    }

    // ── the grants of an administrator role: closed ────────────────────────────────────────────

    @Test
    void refusesANewGrantOnAnAdminRole() {
        assertThatThrownBy(() -> guard.guardCreate("RoleNavigation", List.of(row("roleId", ADMIN))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Tenant Admin")
                .hasMessageContaining("TENANT_ADMIN");
    }

    @Test
    void refusesEditingAnAdminRolesGrantNamedOnlyById() {
        // The payload names only the grant row's id, so the role is only visible by reading it back.
        stubRows("RoleDataScope", row("roleId", ADMIN));

        assertThatThrownBy(() -> guard.guardUpdate("RoleDataScope", List.of(row("id", 99L, "dataScopes", "[{\"scopeType\":\"ALL\"}]"))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void refusesDraggingAGrantOntoAnAdminRole() {
        // Stored row belongs to an ordinary role; the payload re-points it. Checking only the stored
        // side would let this through.
        stubRows("RoleSensitiveFieldSet", row("roleId", ORDINARY));

        assertThatThrownBy(() -> guard.guardUpdate("RoleSensitiveFieldSet", List.of(row("id", 99L, "roleId", ADMIN))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void refusesDeletingAnAdminRolesGrants() {
        stubRows("RoleNavigation", row("roleId", ADMIN));

        assertThatThrownBy(() -> guard.guardByIds("RoleNavigation", List.of(7L, 8L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void readsTheRowsAnUpdateByFilterSelects() {
        // Three-argument signature: the rows come from the filter, the new values from the third arg.
        stubRows("RoleNavigation", row("roleId", ADMIN));

        assertThatThrownBy(() -> guard.guardUpdateByFilter("RoleNavigation",
                new Filters().eq("navigationId", "navigation.payroll"), row("permissionIds", "[]")))
                .isInstanceOf(BusinessException.class);
    }

    // ── the grants of any other role: the tenant's to reshape ───────────────────────────────────

    @Test
    void letsABusinessBuiltInRolesGrantsBeAddedChangedAndRemoved() {
        stubRows("RoleDataScope", row("roleId", BUILT_IN));

        assertThatCode(() -> guard.guardCreate("RoleNavigation", List.of(row("roleId", BUILT_IN))))
                .doesNotThrowAnyException();
        // Widening EMPLOYEE's row scope is now a tenant's decision, gated by the role-management
        // endpoint permission like any other role edit.
        assertThatCode(() -> guard.guardUpdate("RoleDataScope", List.of(row("id", 99L, "dataScopes", "[{\"scopeType\":\"ALL\"}]"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.guardByIds("RoleDataScope", List.of(11L, 12L)))
                .doesNotThrowAnyException();
    }

    @Test
    void letsAnOrdinaryRolesGrantsThrough() {
        stubRows("RoleNavigation", row("roleId", ORDINARY));

        assertThatCode(() -> guard.guardUpdate("RoleNavigation", List.of(row("id", 99L))))
                .doesNotThrowAnyException();
    }

    // ── the Role row itself ───────────────────────────────────────────────────────────────────────

    @Test
    void refusesEditingAnAdminRoleRow() {
        assertThatThrownBy(() -> guard.guardUpdate("Role", List.of(row("id", ADMIN, "name", "Renamed"))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void letsABusinessBuiltInRoleBeRenamedAndDisabled() {
        // The id may arrive as a string; the stored role must still be found, or the rules are skipped.
        assertThatCode(() -> guard.guardUpdate("Role", List.of(
                row("id", String.valueOf(BUILT_IN), "name", "Staff", "active", false, "code", "EMPLOYEE"))))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesChangingABuiltInRolesCode() {
        assertThatThrownBy(() -> guard.guardUpdate("Role", List.of(row("id", String.valueOf(BUILT_IN), "code", "STAFF"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be changed");
    }

    @Test
    void refusesClearingABuiltInRolesCode() {
        // Clearing it would turn the role into an ordinary one — and an ordinary role can be deleted.
        assertThatThrownBy(() -> guard.guardUpdate("Role", List.of(row("id", BUILT_IN, "code", null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be changed");
    }

    @Test
    void refusesGivingAnOrdinaryRoleACode() {
        // Otherwise any role could be made undeletable after it was created.
        assertThatThrownBy(() -> guard.guardUpdate("Role", List.of(row("id", ORDINARY, "code", "EMPLOYEE_2"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void refusesAnUpdateByFilterThatRewritesCodes() {
        when(modelService.searchList(eq("Role"), any(FlexQuery.class)))
                .thenReturn(List.of(role(BUILT_IN, "EMPLOYEE", "Employee")));

        assertThatThrownBy(() -> guard.guardUpdateByFilter("Role",
                new Filters().eq("active", true), row("code", "STAFF")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void refusesDeletingOrCopyingABuiltInRole() {
        assertThatThrownBy(() -> guard.guardByIds("Role", List.of(BUILT_IN)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot delete or copy built-in role");
    }

    @Test
    void letsAnOrdinaryRoleBeDeleted() {
        assertThatCode(() -> guard.guardByIds("Role", List.of(ORDINARY)))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesMintingARoleThatClaimsACode() {
        // Otherwise anyone who can name their own code can declare a role untouchable, and the generic
        // /Role/createOne skips RoleController's own check.
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
        // legitimately write any role's grants. A user request never carries this flag.
        Context system = new Context();
        system.setSkipPermissionCheck(true);

        assertThatCode(() -> ContextHolder.runWith(system,
                () -> guard.guardCreate("RoleNavigation", List.of(row("roleId", ADMIN)))))
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
