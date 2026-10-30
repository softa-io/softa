package io.softa.starter.permission.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.index.EndpointIndex;
import io.softa.starter.permission.interceptor.AdmittedRequest;
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
    private static final String EDIT_CONTRACT = "permission.contract.edit";
    private static final String VOID_CONTRACT = "permission.contract.void";

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
        when(index.customWritePermissions("Contract")).thenReturn(Set.of(EDIT_CONTRACT, VOID_CONTRACT));
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
        return granted("Employee", access);
    }

    private Set<Long> granted(String model, AccessType access) {
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        ctx.setUserId(USER);
        return ContextHolder.callWith(ctx, () -> service.getGrantedCompanyIds(model, access));
    }

    /** Runs {@code body} inside a request the endpoint gate admitted on {@code uri} by {@code permissions}. */
    private <T> T admittedOn(String uri, Set<String> permissions, java.util.function.Supplier<T> body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            AdmittedRequest.record(request, uri, permissions);
            return body.get();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    // ---------------------------------------------------------------- written only through custom endpoints

    @Test
    @DisplayName("a save through a custom endpoint is bounded by the roles holding the permission that admitted it")
    void aCustomSaveIsBoundedByTheAdmittingRoles() {
        // No role holds the contract's standard update, so every role used to count — the self-service
        // role's "every company" among them.
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW), null),
                role(2, Set.of(EDIT_CONTRACT), Set.of(10L)),
                role(3, Set.of(VOID_CONTRACT), null)));

        assertThat(admittedOn("/Contract/editUploaded", Set.of(EDIT_CONTRACT),
                () -> granted("Contract", AccessType.UPDATE))).containsExactly(10L);
    }

    @Test
    @DisplayName("a picker for a model written only through custom endpoints asks the roles listing one")
    void aPickerAsksTheCustomWriters() {
        // The picker's own request is admitted on the company list, not on the contract.
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW), null),
                role(2, Set.of(EDIT_CONTRACT), Set.of(10L)),
                role(3, Set.of(VOID_CONTRACT), Set.of(20L))));

        assertThat(admittedOn("/Company/searchName", Set.of("permission.company.view"),
                () -> granted("Contract", AccessType.UPDATE))).containsExactly(10L, 20L);
        assertThat(granted("Contract", AccessType.CREATE)).containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("a model no permission writes at all still falls back to every role")
    void nothingNamesTheWriterFallsBackToEveryRole() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW), null),
                role(2, Set.of(EDIT_CONTRACT), Set.of(10L))));

        assertThat(granted("Ledger", AccessType.UPDATE)).isNull();
    }

    @Test
    @DisplayName("a holder of the standard write still decides, whatever admitted the request")
    void theStandardHolderStillDecides() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, CREATE), Set.of(10L)),
                role(2, Set.of(EDIT_CONTRACT), null)));

        assertThat(admittedOn("/Employee/hire", Set.of(EDIT_CONTRACT),
                () -> granted(AccessType.CREATE))).containsExactly(10L);
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
