package io.softa.starter.user.event;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventFactoryTest {

    @Test
    void userRoleRelChanged_forUser_wrapsSingleId() {
        UserRoleRelChangedEvent ev = UserRoleRelChangedEvent.forUser(10L, 42L);
        assertThat(ev.tenantId()).isEqualTo(10L);
        assertThat(ev.userIds()).containsExactly(42L);
    }

    @Test
    void userRoleRelChanged_forUsers_setIsImmutable() {
        Set<Long> mutable = new HashSet<>();
        mutable.add(1L);
        mutable.add(2L);
        UserRoleRelChangedEvent ev = UserRoleRelChangedEvent.forUsers(10L, mutable);

        // Set.copyOf → the caller mutating their original set must not
        // affect the event.
        mutable.add(3L);
        assertThat(ev.userIds()).containsExactlyInAnyOrder(1L, 2L);

        // And the copied set itself is immutable.
        assertThatThrownBy(() -> ev.userIds().add(99L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void userRoleRelChanged_directConstructor_keepsCallerSet() {
        // Record accessor exposes the exact set passed in.
        Set<Long> input = Set.of(7L, 8L);
        UserRoleRelChangedEvent ev = new UserRoleRelChangedEvent(10L, input);
        assertThat(ev.userIds()).isSameAs(input);
    }

    @Test
    void userRoleRelChanged_tenantIdMayBeNull() {
        // Publishers running outside a tenant context still fire — the
        // listener decides to skip on null tenantId. Constructor doesn't
        // reject null.
        UserRoleRelChangedEvent ev = new UserRoleRelChangedEvent(null, Set.of(1L));
        assertThat(ev.tenantId()).isNull();
    }

    @Test
    void roleNavigationChanged_carriesTenantAndRoleId() {
        RoleNavigationChangedEvent ev = new RoleNavigationChangedEvent(10L, 500L);
        assertThat(ev.tenantId()).isEqualTo(10L);
        assertThat(ev.roleId()).isEqualTo(500L);
    }

    @Test
    void roleNavigationChanged_allowsNulls() {
        // Publishers pass null tenant / roleId in edge paths; listener
        // decides how to react. The record itself is permissive.
        RoleNavigationChangedEvent ev = new RoleNavigationChangedEvent(null, null);
        assertThat(ev.tenantId()).isNull();
        assertThat(ev.roleId()).isNull();
    }
}
