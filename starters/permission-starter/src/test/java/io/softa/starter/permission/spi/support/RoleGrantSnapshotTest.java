package io.softa.starter.permission.spi.support;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.sensitive.SensitiveFieldSetCache;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.RoleGrant;
import io.softa.starter.permission.spi.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The snapshot keeps each role's grant pair apart, beside the unions the menu plane reads — and a
 * condition rides with the role and model it was written for.
 */
class RoleGrantSnapshotTest {

    private static final Long TENANT = 7L;
    private static final Long USER = 42L;

    private static <T> T view(Class<T> type, Map<String, Object> values) {
        return JsonUtils.stringToObject(JsonUtils.objectToString(values), type);
    }

    private static JsonNode json(String text) {
        return JsonUtils.stringToObject(text, JsonNode.class);
    }

    @SuppressWarnings("unchecked")
    private static PermissionInfo build() {
        ModelService<Long> modelService = mock(ModelService.class);
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class))).thenReturn(List.of());
        when(modelService.searchList(eq("UserRoleRel"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.UserRoleRelView.class, Map.of("roleId", 1)),
                        view(DefaultPermissionSnapshotProvider.UserRoleRelView.class, Map.of("roleId", 2))));
        when(modelService.searchList(eq("Role"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.RoleView.class,
                                Map.of("id", 1, "name", "Directory", "active", true)),
                        view(DefaultPermissionSnapshotProvider.RoleView.class,
                                Map.of("id", 2, "name", "Department HR", "active", true))));
        when(modelService.searchList(eq("RoleNavigation"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.RoleNavigationView.class, Map.of(
                                "roleId", 1, "navigationId", "nav.employee",
                                "permissionIds", List.of("employee.view"))),
                        view(DefaultPermissionSnapshotProvider.RoleNavigationView.class, Map.of(
                                "roleId", 2, "navigationId", "nav.employee",
                                "permissionIds", List.of("employee.view", "employee.update")))));
        when(modelService.searchList(eq("RoleDataScope"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.RoleDataScopeView.class, Map.of(
                                "roleId", 1, "model", "Employee",
                                "dataScopes", List.of(Map.of("scopeType", "ALL")))),
                        view(DefaultPermissionSnapshotProvider.RoleDataScopeView.class, Map.of(
                                "roleId", 2, "model", "Employee",
                                "dataScopes", List.of(Map.of("scopeType", "MANAGED_DEPARTMENTS")),
                                "scopeCondition", List.of(List.of(
                                        "employeeProfileId.residenceStatus", "!=", "SG_EmploymentPass"))))));
        when(modelService.searchList(eq("RoleSensitiveFieldSet"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(view(DefaultPermissionSnapshotProvider.RoleSfsView.class,
                        Map.of("roleId", 2, "sensitiveFieldSetId", "ipa-details"))));
        when(modelService.searchList(eq("Navigation"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of());
        SensitiveFieldSetCache sets = mock(SensitiveFieldSetCache.class);
        when(sets.modelOf("ipa-details")).thenReturn("Employee");
        return new DefaultPermissionSnapshotProvider(null, modelService, sets, () -> null, List.of(), List.of())
                .doLoadFromDb(TENANT, USER);
    }

    @Test
    void eachRoleKeepsItsOwnActionsScopeAndSensitiveSets() {
        PermissionInfo info = build();

        assertThat(info.getRoleGrants()).hasSize(2);
        RoleGrant directory = info.getRoleGrants().get(0);
        RoleGrant departmentHr = info.getRoleGrants().get(1);

        assertThat(directory.getRoleName()).isEqualTo("Directory");
        assertThat(directory.getPermissions()).containsExactly("employee.view");
        assertThat(directory.getModelScopeMap().get("Employee"))
                .extracting(r -> r.getScopeType()).containsExactly(ScopeType.ALL);
        assertThat(directory.getModelSensitiveFieldSetsMap()).isEmpty();

        assertThat(departmentHr.getPermissions()).containsExactlyInAnyOrder("employee.view", "employee.update");
        assertThat(departmentHr.getModelScopeMap().get("Employee"))
                .extracting(r -> r.getScopeType()).containsExactly(ScopeType.MANAGED_DEPARTMENTS);
        assertThat(departmentHr.getModelSensitiveFieldSetsMap().get("Employee")).containsExactly("ipa-details");
    }

    @Test
    void theConditionRidesWithItsRoleAndModel() {
        PermissionInfo info = build();

        assertThat(info.getRoleGrants().get(0).getModelScopeConditions()).isEmpty();
        assertThat(info.getRoleGrants().get(1).getModelScopeConditions().get("Employee"))
                .isEqualTo(json("[[\"employeeProfileId.residenceStatus\",\"!=\",\"SG_EmploymentPass\"]]"));
    }

    @Test
    void theUnionsStayForTheMenuPlane() {
        PermissionInfo info = build();

        assertThat(info.getPermissions()).containsExactlyInAnyOrder("employee.view", "employee.update");
        assertThat(info.getModelScopeMap().get("Employee"))
                .extracting(r -> r.getScopeType())
                .containsExactlyInAnyOrder(ScopeType.ALL, ScopeType.MANAGED_DEPARTMENTS);
        assertThat(info.getModelSensitiveFieldSetsMap().get("Employee")).containsExactly("ipa-details");
    }
}
