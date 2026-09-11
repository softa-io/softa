package io.softa.starter.permission.spi.support;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.spi.PermissionInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two prefix lists, deliberately disjoint, answering two different questions.
 *
 * <p>{@code platform-nav-prefixes} is what a tenant admin is kept OUT of. {@code shared-nav-prefixes}
 * is what BOTH audiences are let into. Messaging belongs to the second: every model under it is
 * multiTenant, so the platform is not looking at another module — it reads the same menus on its own
 * tier, where the verification-code and password-reset mails live.
 *
 * <p>Folding the two into one list would name system and studio twice, once under each meaning. What
 * is pinned here is that they stay separate: the tenant admin must NOT lose messaging, and the
 * platform admin must GET it.
 */
class PlatformAdminNavScopeTest {

    private static final List<String> PLATFORM = List.of("navigation.system.", "navigation.studio.");
    private static final List<String> SHARED = List.of("navigation.message.");

    @SuppressWarnings("unchecked")
    private static DefaultPermissionSnapshotProvider providerOver(String... navIds) {
        ModelService<Long> modelService = mock(ModelService.class);
        when(modelService.searchList(eq("UserRoleRel"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(rel()));
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class))).thenReturn(List.of());
        when(modelService.searchList(eq("Role"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(role("SUPER_ADMIN")));
        when(modelService.searchList(eq("Navigation"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(java.util.Arrays.stream(navIds).map(PlatformAdminNavScopeTest::nav).toList());
        when(modelService.searchList(eq("Permission"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of());
        return new DefaultPermissionSnapshotProvider(null, modelService, null, () -> null,
                PLATFORM, SHARED);
    }

    private static DefaultPermissionSnapshotProvider.UserRoleRelView rel() {
        DefaultPermissionSnapshotProvider.UserRoleRelView r =
                new DefaultPermissionSnapshotProvider.UserRoleRelView();
        r.setRoleId(1L);
        return r;
    }

    private static DefaultPermissionSnapshotProvider.RoleView role(String code) {
        DefaultPermissionSnapshotProvider.RoleView r = new DefaultPermissionSnapshotProvider.RoleView();
        r.setId(1L);
        r.setCode(code);
        r.setActive(true);
        return r;
    }

    private static DefaultPermissionSnapshotProvider.NavigationView nav(String id) {
        DefaultPermissionSnapshotProvider.NavigationView n = new DefaultPermissionSnapshotProvider.NavigationView();
        n.setId(id);
        return n;
    }

    @Test
    void thePlatformAdminGetsItsOwnModulesAndTheSharedOnes() {
        PermissionInfo info = providerOver(
                "navigation.system.tenant-data.tenant-info",
                "navigation.studio.models",
                "navigation.message.email.templates",
                "navigation.core-hr.employee.employee").doLoadFromDb(7L, 42L);

        assertThat(info.getNavigations()).containsExactlyInAnyOrder(
                "navigation.system.tenant-data.tenant-info",
                "navigation.studio.models",
                "navigation.message.email.templates");
    }

    @Test
    void aTenantBusinessModuleStaysOut() {
        // The paired case: a provider that simply returned every navigation would satisfy the
        // assertion above, and C5 would be a hidden sidebar rather than a boundary.
        PermissionInfo info = providerOver(
                "navigation.system.tenant-data.tenant-info",
                "navigation.payroll.pay-item").doLoadFromDb(7L, 42L);

        assertThat(info.getNavigations()).doesNotContain("navigation.payroll.pay-item");
    }

    @Test
    void withNoSharedListConfiguredMessagingIsNotReachable() {
        // Proves messaging arrives through the SHARED list and not by being quietly folded into the
        // platform one — the folding this design exists to avoid.
        ModelService<Long> modelService = mock(ModelService.class);
        when(modelService.searchList(eq("UserRoleRel"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(rel()));
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class))).thenReturn(List.of());
        when(modelService.searchList(eq("Role"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(role("SUPER_ADMIN")));
        when(modelService.searchList(eq("Navigation"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(nav("navigation.message.email.templates")));
        when(modelService.searchList(eq("Permission"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of());

        PermissionInfo info = new DefaultPermissionSnapshotProvider(null, modelService, null,
                () -> null, PLATFORM, List.of()).doLoadFromDb(7L, 42L);

        assertThat(info.getNavigations()).isEmpty();
    }
}
