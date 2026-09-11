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
 * The consultant principal is derived from the membership, not from a stored role row.
 *
 * <p>Worth its own class because of how the missing half failed. Everything downstream of the role
 * code was written first — seven data-plane call sites moved onto {@code hasFullDataAccess}, the menu
 * routing added — and every one of them was unreachable, because nothing ever put {@code CONSULTANT}
 * into {@code roleCodes}. Nothing raised: the consultant simply logged in to a tenant with no menus
 * and no rows, which reads as a seeding problem rather than as dead code.
 *
 * <p>So the assertions are deliberately paired. Deriving the code is asserted together with the
 * snapshot it routes to, because the derivation alone proves nothing; and the non-consultant case is
 * asserted beside it, because a predicate that answered true unconditionally would satisfy the first
 * test and hand every roleless user in the deployment a tenant admin's menus.
 */
class ConsultantRoleDerivationTest {

    private static final Long TENANT = 7L;
    private static final Long ACCOUNT = 42L;

    /**
     * A provider whose account read reports the given consultant flag, and whose nav / permission
     * catalogue holds one entitled navigation. Roles are empty throughout: a consultant holds none,
     * which is the whole point — the old code reached {@code activeRoles.isEmpty()} and stopped.
     */
    @SuppressWarnings("unchecked")
    private static DefaultPermissionSnapshotProvider providerFor(Object consultantFlag) {
        ModelService<Long> modelService = mock(ModelService.class);
        // No stored roles for this account.
        when(modelService.searchList(eq("UserRoleRel"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of());
        // The membership itself — the only place the consultant fact lives.
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class)))
                .thenReturn(consultantFlag == null
                        ? List.of()
                        : List.of(Map.of("consultant", consultantFlag)));
        // The catalogue a tenant-admin-shaped snapshot is computed from.
        when(modelService.searchList(eq("Navigation"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(navigation("navigation.hcm.employee")));
        when(modelService.searchList(eq("Permission"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(permission("employee.view", "navigation.hcm.employee")));
        return new DefaultPermissionSnapshotProvider(null, modelService, null, () -> null, List.of(), List.of());
    }

    private static DefaultPermissionSnapshotProvider.NavigationView navigation(String id) {
        DefaultPermissionSnapshotProvider.NavigationView n = new DefaultPermissionSnapshotProvider.NavigationView();
        n.setId(id);
        return n;
    }

    private static DefaultPermissionSnapshotProvider.PermissionView permission(String id, String navId) {
        DefaultPermissionSnapshotProvider.PermissionView p = new DefaultPermissionSnapshotProvider.PermissionView();
        p.setId(id);
        p.setNavigationId(navId);
        return p;
    }

    @Test
    void consultantMembershipIsTheThirdPrincipal() {
        PermissionInfo info = providerFor(Boolean.TRUE).doLoadFromDb(TENANT, ACCOUNT);

        // The code the whole feature hangs off, derived with no role row behind it.
        assertThat(info.getRoleCodes()).contains("CONSULTANT");
        assertThat(info.isConsultant()).isTrue();
        // The data plane: what the seven moved call sites ask.
        assertThat(info.hasFullDataAccess()).isTrue();
        // The menu plane: the tenant's entitled set, not the empty snapshot a roleless user gets.
        assertThat(info.getNavigations()).containsExactly("navigation.hcm.employee");
        assertThat(info.getPermissions()).containsExactly("employee.view");
    }

    @Test
    void aRolelessOrdinaryMembershipStaysEmpty() {
        PermissionInfo info = providerFor(Boolean.FALSE).doLoadFromDb(TENANT, ACCOUNT);

        assertThat(info.getRoleCodes()).doesNotContain("CONSULTANT");
        assertThat(info.isConsultant()).isFalse();
        assertThat(info.hasFullDataAccess()).isFalse();
        // The point of the pair: a predicate stuck on true would hand this user the menus above.
        assertThat(info.getNavigations()).isEmpty();
        assertThat(info.getPermissions()).isEmpty();
    }

    @Test
    void aMissingMembershipRowIsNotAConsultant() {
        // The account read comes back empty when the id names no row — a stale session, a deleted
        // membership. Fail closed rather than treating "unknown" as "consultant".
        PermissionInfo info = providerFor(null).doLoadFromDb(TENANT, ACCOUNT);

        assertThat(info.isConsultant()).isFalse();
        assertThat(info.getNavigations()).isEmpty();
    }

    @Test
    void theFlagIsReadThroughWhicheverWayTheDriverMapsIt() {
        // MySQL hands a TINYINT(1) back as an Integer through the generic map read, not a Boolean.
        // Read only as a Boolean, the derivation silently answers false on a live database while
        // every mock-based test above stays green.
        assertThat(providerFor(1).doLoadFromDb(TENANT, ACCOUNT).isConsultant()).isTrue();
        assertThat(providerFor(0).doLoadFromDb(TENANT, ACCOUNT).isConsultant()).isFalse();
    }
}
