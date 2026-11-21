package io.softa.starter.permission.interceptor;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.permission.index.EndpointIndex;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether other users could call an endpoint, asked of each one's snapshot by the gate's own rules — so that work
 * is not handed to someone the gate would then refuse.
 */
class SnapshotEndpointAccessCheckerTest {

    private static final Long TENANT = 5L;
    private static final String APPROVE = "/flow/instances/approve";

    private final EndpointIndex endpointIndex = mock(EndpointIndex.class);
    private final PermissionSnapshotProvider snapshots = mock(PermissionSnapshotProvider.class);
    private final PermissionInterceptorProperties properties = new PermissionInterceptorProperties();
    private final SnapshotEndpointAccessChecker checker =
            new SnapshotEndpointAccessChecker(endpointIndex, snapshots, properties);

    @BeforeEach
    void registered() {
        when(endpointIndex.lookup(APPROVE, "POST")).thenReturn(Set.of("permission.home.my-tasks.approve"));
    }

    /** Asked inside the tenant, as a request of it asks. */
    private Set<Long> withoutAccess(Collection<Long> userIds, String method, String path) {
        Context context = new Context();
        context.setTenantId(TENANT);
        return ContextHolder.callWith(context, () -> checker.withoutAccess(userIds, method, path));
    }

    private void holds(Long userId, Set<String> permissions, String... roleCodes) {
        when(snapshots.get(TENANT, userId)).thenReturn(PermissionInfo.builder()
                .permissions(permissions).roleCodes(Set.of(roleCodes)).build());
    }

    @Test
    void someoneWithoutAPermissionListingTheEndpoint_couldNotCallIt() {
        holds(1L, Set.of("permission.home.my-tasks.approve", "permission.payslip.view"));
        holds(2L, Set.of("permission.payslip.view"));
        // No snapshot at all: the gate fails closed on one too.
        when(snapshots.get(TENANT, 3L)).thenReturn(null);

        assertThat(withoutAccess(List.of(1L, 2L, 3L), "POST", APPROVE)).containsExactly(2L, 3L);
    }

    @Test
    void aTenantAdmin_isAnsweredByThePermissionsOfThePlan_likeAtTheGate() {
        holds(1L, Set.of("permission.home.my-tasks.approve"), PermissionInfo.CODE_TENANT_ADMIN);
        holds(2L, Set.of(), PermissionInfo.CODE_TENANT_ADMIN);

        assertThat(withoutAccess(List.of(1L, 2L), "POST", APPROVE)).containsExactly(2L);
    }

    @Test
    void anEndpointEveryoneSignedInMayCall_isOpenToAll() {
        properties.setAuthenticatedBypassPatterns(List.of("/me/**"));

        assertThat(withoutAccess(List.of(1L, 2L), "GET", "/me/profile")).isEmpty();
    }

    @Test
    void anEndpointNoPermissionLists_isOpenToThePlatformAdministratorAlone() {
        holds(1L, Set.of(), PermissionInfo.CODE_SUPER_ADMIN);
        holds(2L, Set.of("permission.home.my-tasks.approve"));

        assertThat(withoutAccess(List.of(1L, 2L), "POST", "/unregistered")).containsExactly(2L);
    }

    @Test
    void outsideATenant_nobodyCanBeSaidToPass() {
        assertThat(checker.withoutAccess(List.of(1L), "POST", APPROVE)).containsExactly(1L);
    }
}
