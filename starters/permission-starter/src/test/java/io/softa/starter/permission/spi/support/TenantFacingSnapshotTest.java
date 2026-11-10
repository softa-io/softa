package io.softa.starter.permission.spi.support;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.EntitlementService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.sensitive.SensitiveFieldSetCache;
import io.softa.starter.permission.spi.PermissionInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A customer tenant's user holds only the tenant-facing part of what their roles store: no platform
 * navigation, no unentitled module, no platform permission filed under a tenant page. The platform
 * tenant keeps every grant.
 */
class TenantFacingSnapshotTest {

    private static final Long TENANT = 7L;
    private static final Long USER = 42L;

    private static <T> T view(Class<T> type, Map<String, Object> values) {
        return JsonUtils.stringToObject(JsonUtils.objectToString(values), type);
    }

    @SuppressWarnings("unchecked")
    private static PermissionInfo build(Long tenantId) {
        ModelService<Long> modelService = mock(ModelService.class);
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class))).thenReturn(List.of());
        when(modelService.searchList(eq("UserRoleRel"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(view(DefaultPermissionSnapshotProvider.UserRoleRelView.class, Map.of("roleId", 1))));
        when(modelService.searchList(eq("Role"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(view(DefaultPermissionSnapshotProvider.RoleView.class,
                        Map.of("id", 1, "name", "Probe", "active", true))));
        when(modelService.searchList(eq("RoleNavigation"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.RoleNavigationView.class, Map.of(
                                "roleId", 1, "navigationId", "navigation.corehr.employee",
                                "permissionIds", List.of("permission.employee.view",
                                        "permission.system.tenant-info.view"))),
                        view(DefaultPermissionSnapshotProvider.RoleNavigationView.class, Map.of(
                                "roleId", 1, "navigationId", "navigation.system.tenant-data.tenant-info",
                                "permissionIds", List.of("permission.system.tenant-info.update"))),
                        view(DefaultPermissionSnapshotProvider.RoleNavigationView.class, Map.of(
                                "roleId", 1, "navigationId", "navigation.payroll.run",
                                "permissionIds", List.of("permission.payroll.run")))));
        when(modelService.searchList(eq("Permission"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.PermissionView.class, Map.of(
                                "id", "permission.employee.view", "navigationId", "navigation.corehr.employee")),
                        view(DefaultPermissionSnapshotProvider.PermissionView.class, Map.of(
                                "id", "permission.system.tenant-info.view",
                                "navigationId", "navigation.system.tenant-data.tenant-info")),
                        view(DefaultPermissionSnapshotProvider.PermissionView.class, Map.of(
                                "id", "permission.system.tenant-info.update",
                                "navigationId", "navigation.system.tenant-data.tenant-info")),
                        view(DefaultPermissionSnapshotProvider.PermissionView.class, Map.of(
                                "id", "permission.payroll.run", "navigationId", "navigation.payroll.run"))));
        when(modelService.searchList(eq("RoleDataScope"), any(FlexQuery.class), any(Class.class))).thenReturn(List.of());
        when(modelService.searchList(eq("RoleSensitiveFieldSet"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of());
        when(modelService.searchList(eq("Navigation"), any(FlexQuery.class), any(Class.class))).thenReturn(List.of());
        EntitlementService entitlement = mock(EntitlementService.class);
        when(entitlement.entitledModules(anyLong())).thenReturn(Set.of("corehr"));
        DefaultPermissionSnapshotProvider provider = new DefaultPermissionSnapshotProvider(null, modelService,
                mock(SensitiveFieldSetCache.class), () -> null,
                List.of("navigation.system.", "navigation.studio."), List.of());
        ReflectionTestUtils.setField(provider, "entitlementService", entitlement);
        provider.build();
        return provider.doLoadFromDb(tenantId, USER);
    }

    @Test
    void aCustomerTenantKeepsOnlyItsOwnEntitledGrants() {
        PermissionInfo info = build(TENANT);

        assertThat(info.getNavigations()).containsExactly("navigation.corehr.employee");
        assertThat(info.getPermissions()).containsExactly("permission.employee.view");
        assertThat(info.getRoleGrants().getFirst().getPermissions()).containsExactly("permission.employee.view");
    }

    @Test
    void thePlatformTenantKeepsEveryGrant() {
        PermissionInfo info = build(BaseConstant.PLATFORM_TENANT_ID);

        assertThat(info.getPermissions()).containsExactlyInAnyOrder("permission.employee.view",
                "permission.system.tenant-info.view", "permission.system.tenant-info.update",
                "permission.payroll.run");
    }
}
