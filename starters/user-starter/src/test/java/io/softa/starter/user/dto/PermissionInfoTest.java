package io.softa.starter.user.dto;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.softa.starter.user.constant.RoleConstant;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionInfoTest {

    @Test
    void isSuperAdmin_holderRole_returnsTrue() {
        PermissionInfo pi = PermissionInfo.builder()
                .roleCodes(Set.of(RoleConstant.CODE_SUPER_ADMIN))
                .build();
        assertThat(pi.isSuperAdmin()).isTrue();
    }

    @Test
    void isSuperAdmin_regularRole_returnsFalse() {
        PermissionInfo pi = PermissionInfo.builder()
                .roleCodes(Set.of("HR_MANAGER"))
                .build();
        assertThat(pi.isSuperAdmin()).isFalse();
    }

    @Test
    void isSuperAdmin_emptyRoleCodes_returnsFalse() {
        PermissionInfo pi = PermissionInfo.builder().roleCodes(Set.of()).build();
        assertThat(pi.isSuperAdmin()).isFalse();
    }

    @Test
    void isSuperAdmin_nullRoleCodes_returnsFalseWithoutThrowing() {
        PermissionInfo pi = new PermissionInfo();
        assertThat(pi.isSuperAdmin()).isFalse();
    }

    @Test
    void isSuperAdmin_static_nullPi_returnsFalse() {
        assertThat(PermissionInfo.isSuperAdmin(null)).isFalse();
    }

    @Test
    void isSuperAdmin_static_delegatesToInstance() {
        PermissionInfo pi = PermissionInfo.builder()
                .roleCodes(Set.of(RoleConstant.CODE_SUPER_ADMIN))
                .build();
        assertThat(PermissionInfo.isSuperAdmin(pi)).isTrue();
    }
}
