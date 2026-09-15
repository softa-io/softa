package io.softa.starter.permission.service;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.softa.starter.permission.spi.PermissionInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A consultant is a third kind of principal, and the line between the two planes is the design.
 *
 * <p>DATA — row scope, field masking, write guards — treats a consultant like an admin: they were
 * brought in to work on the tenant's data. MENUS do not: a consultant gets what the tenant's
 * subscription includes, no more. Folding consultants into {@code isAdmin()} would hand them screens
 * the tenant never bought, and it is a one-word change away at any time, which is why it is pinned.
 */
class ConsultantPrincipalTest {

    private static PermissionInfo withRoles(String... codes) {
        PermissionInfo pi = new PermissionInfo();
        pi.setRoleCodes(Set.of(codes));
        return pi;
    }

    @Test
    void aConsultantReadsDataLikeAnAdmin() {
        PermissionInfo consultant = withRoles("CONSULTANT");

        assertThat(consultant.hasFullDataAccess()).isTrue();
        assertThat(PermissionInfo.hasFullDataAccess(consultant)).isTrue();
    }

    @Test
    void aConsultantIsNotAnAdmin_soTheMenuPlaneStillBoundsThem() {
        PermissionInfo consultant = withRoles("CONSULTANT");

        // The load-bearing assertion. Endpoint and navigation checks ask isAdmin(); if a consultant
        // ever answered true there, the subscription would stop bounding what they can reach.
        assertThat(consultant.isAdmin()).isFalse();
        assertThat(consultant.isSuperAdmin()).isFalse();
        assertThat(consultant.isTenantAdmin()).isFalse();
    }

    @Test
    void adminsKeepFullDataAccess() {
        assertThat(withRoles("SUPER_ADMIN").hasFullDataAccess()).isTrue();
        assertThat(withRoles("TENANT_ADMIN").hasFullDataAccess()).isTrue();
    }

    @Test
    void anOrdinaryRoleGetsNeither() {
        PermissionInfo employee = withRoles("HR_ADMIN");

        assertThat(employee.hasFullDataAccess()).isFalse();
        assertThat(employee.isConsultant()).isFalse();
    }

    @Test
    void nullPrincipalIsRefusedRatherThanCrashing() {
        assertThat(PermissionInfo.hasFullDataAccess(null)).isFalse();
        assertThat(PermissionInfo.isConsultant(null)).isFalse();
    }
}
