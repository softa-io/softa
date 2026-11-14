package io.softa.starter.permission.spi.support;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.sensitive.SensitiveFieldSetCache;
import io.softa.starter.permission.spi.PermissionInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A tenant's own role never reaches the platform's menus, whatever it was granted: a grant saved
 * before a menu became platform-only, or written around the role wizard, opens nothing — neither the
 * navigation nor a platform permission tucked into another navigation's row. The platform tenant's own
 * roles keep theirs.
 */
class TenantRolePlatformNavTest {

    private static final Long TENANT = 7L;
    private static final Long USER = 42L;
    private static final String PLATFORM_NAV = "navigation.message.sms.sms-send-record";
    private static final String TENANT_NAV = "navigation.message.mail.mail-send-record";

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
                        Map.of("id", 1, "name", "Messaging Support", "active", true))));
        when(modelService.searchList(eq("RoleNavigation"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.RoleNavigationView.class, Map.of(
                                "roleId", 1, "navigationId", PLATFORM_NAV,
                                "permissionIds", List.of("permission.message.sms-send-record-read"))),
                        view(DefaultPermissionSnapshotProvider.RoleNavigationView.class, Map.of(
                                "roleId", 1, "navigationId", TENANT_NAV,
                                // a platform permission tucked into a tenant navigation's row
                                "permissionIds", List.of("permission.message.mail-send-record-read",
                                        "permission.message.sms-send-record-retry")))));
        when(modelService.searchList(eq("Permission"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.PermissionView.class, Map.of(
                                "id", "permission.message.sms-send-record-read", "navigationId", PLATFORM_NAV)),
                        view(DefaultPermissionSnapshotProvider.PermissionView.class, Map.of(
                                "id", "permission.message.sms-send-record-retry", "navigationId", PLATFORM_NAV)),
                        view(DefaultPermissionSnapshotProvider.PermissionView.class, Map.of(
                                "id", "permission.message.mail-send-record-read", "navigationId", TENANT_NAV))));
        when(modelService.searchList(eq("RoleDataScope"), any(FlexQuery.class), any(Class.class))).thenReturn(List.of());
        when(modelService.searchList(eq("RoleSensitiveFieldSet"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of());
        when(modelService.searchList(eq("Navigation"), any(FlexQuery.class), any(Class.class))).thenReturn(List.of());
        DefaultPermissionSnapshotProvider provider = new DefaultPermissionSnapshotProvider(null, modelService,
                mock(SensitiveFieldSetCache.class), () -> null, List.of("navigation.message.sms."), List.of());
        provider.build();
        return provider.doLoadFromDb(tenantId, USER);
    }

    @Test
    void aTenantsRoleLosesThePlatformMenusItWasGranted() {
        PermissionInfo info = build(TENANT);

        assertThat(info.getNavigations()).doesNotContain(PLATFORM_NAV).contains(TENANT_NAV);
        assertThat(info.getPermissions()).containsExactly("permission.message.mail-send-record-read");
    }

    @Test
    void thePlatformTenantsRoleKeepsThem() {
        PermissionInfo info = build(BaseConstant.PLATFORM_TENANT_ID);

        assertThat(info.getNavigations()).contains(PLATFORM_NAV, TENANT_NAV);
        assertThat(info.getPermissions()).contains("permission.message.sms-send-record-read",
                "permission.message.sms-send-record-retry", "permission.message.mail-send-record-read");
    }
}
