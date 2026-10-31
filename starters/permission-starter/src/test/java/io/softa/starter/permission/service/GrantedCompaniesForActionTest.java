package io.softa.starter.permission.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.index.EndpointIndex;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;
import io.softa.starter.permission.spi.RoleGrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which companies a write may name: the grants of the roles holding the write, not of every role.
 *
 * <p>The case it exists for: an HR role that creates employees for one company, held beside the
 * self-service role every user has, which carries no company grant and so reaches every company. Asked
 * of all roles, the answer is "every company"; asked of the role that creates, it is the one.
 */
class GrantedCompaniesForActionTest {

    private static final long TENANT = 1L;
    private static final long USER = 2L;
    private static final String CREATE = "permission.employee.create";
    private static final String VIEW = "permission.employee.view";

    private PermissionInfo pi;
    private PermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        pi = new PermissionInfo();
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenAnswer(inv -> pi);
        EndpointIndex index = mock(EndpointIndex.class);
        when(index.modelActionPermissions("Employee", "create")).thenReturn(Set.of(CREATE));
        when(index.modelActionPermissions("Employee", "view")).thenReturn(Set.of(VIEW));
        @SuppressWarnings("unchecked")
        ModelService<Long> modelService = mock(ModelService.class);
        service = new PermissionServiceImpl(provider, mock(ScopeRuleCompiler.class), null, modelService,
                mock(ScopeApplicabilityResolver.class), () -> index);
    }

    private static RoleGrant role(long id, Set<String> permissions, Set<Long> companies) {
        RoleGrant g = new RoleGrant();
        g.setRoleId(id);
        g.setPermissions(new HashSet<>(permissions));
        g.setModelScopeMap(Map.of());
        g.setModelScopeConditions(Map.of());
        g.setModelSensitiveFieldSetsMap(Map.of());
        g.setGrantedCompanyIds(companies);
        return g;
    }

    private Set<Long> granted(AccessType access) {
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        ctx.setUserId(USER);
        return ContextHolder.callWith(ctx, () -> service.getGrantedCompanyIds("Employee", access));
    }

    @Test
    @DisplayName("a create reaches the creating role's companies, not the self-service role's every company")
    void aCreateIsBoundedByTheRoleThatCreates() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW), null),
                role(2, Set.of(VIEW, CREATE), Set.of(10L))));

        assertThat(granted(AccessType.CREATE)).containsExactly(10L);
        assertThat(granted(AccessType.READ)).isNull();
    }

    @Test
    @DisplayName("roles that both create add their companies up")
    void creatingRolesAddUp() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(CREATE), Set.of(10L)),
                role(2, Set.of(CREATE), Set.of(20L))));

        assertThat(granted(AccessType.CREATE)).containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("a creating role with no company grant is unrestricted")
    void anUngrantedCreatorIsUnrestricted() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(CREATE), Set.of(10L)),
                role(2, Set.of(CREATE), null)));

        assertThat(granted(AccessType.CREATE)).isNull();
    }

    @Test
    @DisplayName("a creating role granted no company reaches none")
    void anEmptyGrantReachesNone() {
        pi.setRoleGrants(List.of(role(1, Set.of(CREATE), Set.of())));

        assertThat(granted(AccessType.CREATE)).isEmpty();
    }

    @Test
    @DisplayName("an admin, and a bypassed caller, are unrestricted")
    void adminAndBypassAreUnrestricted() {
        pi.setRoleCodes(Set.of("TENANT_ADMIN"));
        pi.setRoleGrants(List.of(role(1, Set.of(CREATE), Set.of(10L))));
        assertThat(granted(AccessType.CREATE)).isNull();

        pi.setRoleCodes(Set.of());
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        ctx.setUserId(USER);
        ctx.setSkipPermissionCheck(true);
        assertThat(ContextHolder.callWith(ctx, () -> service.getGrantedCompanyIds("Employee", AccessType.CREATE)))
                .isNull();
    }

    @Test
    @DisplayName("no snapshot fails closed")
    void noSnapshotReachesNone() {
        pi = null;

        assertThat(granted(AccessType.CREATE)).isEmpty();
    }
}
