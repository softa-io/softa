package io.softa.starter.permission.spi.support;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.spi.PermissionInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two prefix lists, answering two different questions.
 *
 * <p>{@code platform-nav-prefixes} is what a tenant admin is kept OUT of. {@code shared-nav-prefixes}
 * is what BOTH audiences are let into. Messaging's pages on multiTenant models belong to the second: the
 * platform is not looking at another module — it reads the same menus on its own tier, where the
 * verification-code and password-reset mails live. Its operations pages sit on tables that are not
 * multiTenant, so they are the platform's alone and belong to the first.
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
        return built(new DefaultPermissionSnapshotProvider(null, modelService, null, () -> null,
                PLATFORM, SHARED));
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

    private static DefaultPermissionSnapshotProvider.NavigationView nav(String id, String parentId) {
        DefaultPermissionSnapshotProvider.NavigationView n = nav(id);
        n.setParentId(parentId);
        return n;
    }

    @SuppressWarnings("unchecked")
    private static DefaultPermissionSnapshotProvider providerOver(
            List<DefaultPermissionSnapshotProvider.NavigationView> navs) {
        ModelService<Long> modelService = mock(ModelService.class);
        when(modelService.searchList(eq("UserRoleRel"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(rel()));
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class))).thenReturn(List.of());
        when(modelService.searchList(eq("Role"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(role("SUPER_ADMIN")));
        when(modelService.searchList(eq("Navigation"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(navs);
        when(modelService.searchList(eq("Permission"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of());
        return built(new DefaultPermissionSnapshotProvider(null, modelService, null, () -> null,
                PLATFORM, SHARED));
    }

    @Test
    void theGroupRowsAboveAReachablePageComeBackToo() {
        // The set has to answer the same question the ui-context build answers, and that one expands
        // ancestors. A prefix like `navigation.users.people.` admits the pages without the module row
        // above them, so a caller asking about that row would have been told no by one assembly and
        // yes by the other — two answers to one question, which is how a sidebar and a gate drift.
        PermissionInfo info = providerOver(List.of(
                nav("navigation.system"),
                nav("navigation.system.tenant-data", "navigation.system"),
                nav("navigation.system.tenant-data.tenant-info", "navigation.system.tenant-data")))
                .doLoadFromDb(7L, 42L);

        assertThat(info.getNavigations()).contains(
                "navigation.system", "navigation.system.tenant-data",
                "navigation.system.tenant-data.tenant-info");
    }

    @Test
    void anAncestorOutsideTheBoundaryIsStillNotReached() {
        // The paired case: expansion must not become a way in. A tenant module's own group row is an
        // ancestor of nothing the platform may reach, so it stays out.
        PermissionInfo info = providerOver(List.of(
                nav("navigation.payroll"),
                nav("navigation.payroll.pay-item", "navigation.payroll"),
                nav("navigation.system"),
                nav("navigation.system.tenant-data", "navigation.system")))
                .doLoadFromDb(7L, 42L);

        assertThat(info.getNavigations())
                .doesNotContain("navigation.payroll", "navigation.payroll.pay-item");
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
        // assertion above, and the narrowing would be a hidden sidebar rather than a boundary.
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

        PermissionInfo info = built(new DefaultPermissionSnapshotProvider(null, modelService, null,
                () -> null, PLATFORM, List.of())).doLoadFromDb(7L, 42L);

        assertThat(info.getNavigations()).isEmpty();
    }

    /** As startup leaves it: the navigation ancestry built from the rows stubbed above, the model being present. */
    private static DefaultPermissionSnapshotProvider built(DefaultPermissionSnapshotProvider provider) {
        try (MockedStatic<ModelManager> models = Mockito.mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.existModel("Navigation")).thenReturn(true);
            provider.build();
        }
        return provider;
    }
}
