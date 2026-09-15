package io.softa.starter.permission.interceptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.BuiltinRole;
import io.softa.framework.base.enums.SystemRole;
import io.softa.framework.base.exception.ConfigurationException;
import io.softa.framework.base.exception.PermissionException;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;
import io.softa.starter.permission.index.EndpointIndex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.service.ConsultantAccessChecker;

class PermissionInterceptorTest {

    private EndpointIndex endpointIndex;
    private PermissionSnapshotProvider snapshotProvider;
    private PermissionInterceptorProperties props;
    private PermissionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        endpointIndex = mock(EndpointIndex.class);
        snapshotProvider = mock(PermissionSnapshotProvider.class);
        props = new PermissionInterceptorProperties();
        interceptor = new PermissionInterceptor(endpointIndex, snapshotProvider, props);
    }

    @AfterEach
    void tearDown() {
        // Nothing — ContextHolder uses ScopedValue; test invocations use runWith.
    }

    private static MockHttpServletRequest req(String method, String path) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setServletPath(path);
        return r;
    }

    private static <T> T inCtx(Long tenantId, Long userId, java.util.function.Supplier<T> body) {
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        ctx.setUserId(userId);
        return ContextHolder.callWith(ctx, body::get);
    }

    // ─── public URI bypass ───

    @Test
    void publicUri_bypassesAuth() {
        props.setPublicUriPatterns(List.of("/auth/**"));
        MockHttpServletRequest r = req("POST", "/auth/login");
        assertThat(interceptor.preHandle(r, new MockHttpServletResponse(), null)).isTrue();
    }

    // ─── auth required (no ctx) ───

    @Test
    void authRequired_whenNoContextOrUserId() {
        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        assertThatThrownBy(() -> interceptor.preHandle(r, new MockHttpServletResponse(), null))
                .isInstanceOf(PermissionException.class)
                .hasMessageContaining("Authentication required");
    }

    @Test
    void authRequired_whenContextHasNoUserId() {
        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        Context ctx = new Context();  // no userId
        ContextHolder.runWith(ctx, () ->
                assertThatThrownBy(() ->
                        interceptor.preHandle(r, new MockHttpServletResponse(), null))
                        .isInstanceOf(PermissionException.class));
    }

    @Test
    void tenantIdMissing_throwsConfigurationException() {
        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        Context ctx = new Context();
        ctx.setUserId(42L);   // no tenantId
        ContextHolder.runWith(ctx, () ->
                assertThatThrownBy(() ->
                        interceptor.preHandle(r, new MockHttpServletResponse(), null))
                        .isInstanceOf(ConfigurationException.class)
                        .hasMessageContaining("missing tenant"));
    }

    // ─── authenticated-bypass patterns ───

    @Test
    void authenticatedBypass_skipsPermissionLookup() {
        props.setAuthenticatedBypassPatterns(List.of("/me/**"));
        MockHttpServletRequest r = req("GET", "/me/uiContext");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
        // Never called EndpointIndex/provider for this bypass path.
        org.mockito.Mockito.verify(endpointIndex, org.mockito.Mockito.never())
                .lookup(anyString(), anyString());
    }

    // ─── platform admin: System and Studio, and no longer everything ───
    //
    // This block used to assert the opposite — that a super-admin bypassed the endpoint gate outright
    // and the index was never even consulted. That is retired: tenant business work belongs to the
    // consultant now, done inside the customer that authorized them and only while that authorization
    // lasts. The cases are rewritten rather than deleted, because the old contract is exactly what
    // must not come back by accident.

    /** A platform administrator as platformAdminSnapshot builds one: platform permissions only. */
    private static PermissionInfo platformAdmin() {
        return PermissionInfo.builder()
                .roleCodes(Set.of(PermissionInfo.CODE_SUPER_ADMIN))
                .permissions(Set.of("system.tenant-info.view", "studio.model.view"))
                .build();
    }

    @Test
    void platformAdmin_isDeniedATenantBusinessEndpoint() {
        // The whole point of the narrowing, and the reason it is enforcement rather than a hidden sidebar:
        // without this the module is still one typed URL away.
        when(snapshotProvider.get(eq(10L), eq(42L))).thenReturn(platformAdmin());
        when(endpointIndex.lookup(eq("/Employee/searchList"), eq("POST")))
                .thenReturn(Set.of("core-hr.employee.view"));

        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        inCtx(10L, 42L, () -> {
            assertThatThrownBy(() ->
                    interceptor.preHandle(r, new MockHttpServletResponse(), null))
                    .isInstanceOf(PermissionException.class)
                    .hasMessageContaining("Missing permission");
            return null;
        });
    }

    @Test
    void platformAdmin_reachesItsOwnConsole() {
        when(snapshotProvider.get(eq(10L), eq(42L))).thenReturn(platformAdmin());
        when(endpointIndex.lookup(eq("/TenantInfo/searchPage"), eq("POST")))
                .thenReturn(Set.of("system.tenant-info.view"));

        MockHttpServletRequest r = req("POST", "/TenantInfo/searchPage");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }

    @Test
    void platformAdmin_isNOTdeniedThePlatformOnlyEndpoints() {
        // The lockout this nearly caused. platformOnlyPatterns bars billing / plan / provisioning to
        // everyone INSIDE a tenant — an admin and a consultant alike — precisely because they are the
        // platform's own work. Running the platform administrator through the same denial would take
        // their console away and leave nobody able to provision anything.
        props.setPlatformOnlyPatterns(List.of("/TenantInfo/**"));
        when(snapshotProvider.get(eq(10L), eq(42L))).thenReturn(platformAdmin());
        when(endpointIndex.lookup(anyString(), anyString())).thenReturn(Set.of());

        MockHttpServletRequest r = req("POST", "/TenantInfo/createOne");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }

    @Test
    void platformAdmin_stillReachesAnUnregisteredEndpoint() {
        // Same allowance the other two admin-shaped principals get: plenty of endpoints carry no
        // permission mapping, and denying those would turn this boundary into a broad outage.
        when(snapshotProvider.get(eq(10L), eq(42L))).thenReturn(platformAdmin());
        when(endpointIndex.lookup(anyString(), anyString())).thenReturn(Set.of());

        MockHttpServletRequest r = req("POST", "/SomeUnmappedOps/doIt");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }

    @Test
    void superAdmin_doesNotBecomeCrossTenant() {
        // The bypass is about the permission gate, not about tenant isolation. This used to also set
        // crossTenant, which silently widened every read on the request to all tenants and stopped
        // tenant_id being stamped on every write. Reaching across tenants is now opted into per
        // operation (@CrossTenant / ContextUtils windows), so a super-admin request stays in its tenant.
        PermissionInfo pi = PermissionInfo.builder()
                .roleCodes(Set.of(PermissionInfo.CODE_SUPER_ADMIN))
                .build();
        when(snapshotProvider.get(eq(10L), eq(42L))).thenReturn(pi);

        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        Context ctx = new Context();
        ctx.setTenantId(10L);
        ctx.setUserId(42L);
        ContextHolder.runWith(ctx, () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));

        assertThat(ctx.isCrossTenant()).isFalse();
    }

    @Test
    void superAdmin_stillGetsEverySystemRoleCode() {
        // The role-code bridge is what @RequireRole reads, and it must survive independently of the
        // tenant flag — the two used to be entangled in one branch, so removing the flag could have
        // taken the expansion with it. A super-admin keeps its own code and gains every framework
        // SystemRole, so a system-role gate is never stricter for it than the permission gate.
        PermissionInfo pi = PermissionInfo.builder()
                .roleCodes(Set.of(PermissionInfo.CODE_SUPER_ADMIN))
                .build();
        when(snapshotProvider.get(eq(10L), eq(42L))).thenReturn(pi);

        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        Context ctx = new Context();
        ctx.setTenantId(10L);
        ctx.setUserId(42L);
        ContextHolder.runWith(ctx, () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));

        assertThat(ctx.getRoleCodes()).contains(PermissionInfo.CODE_SUPER_ADMIN);
        assertThat(ctx.getRoleCodes())
                .containsAll(Arrays.stream(SystemRole.values()).map(SystemRole::getCode).toList());
    }

    // ─── unmapped endpoint → 403 ───

    @Test
    void unmappedEndpoint_throwsPermissionException() {
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(
                PermissionInfo.builder().roleCodes(Set.of("HR")).build());
        when(endpointIndex.lookup(anyString(), anyString())).thenReturn(Set.of());

        MockHttpServletRequest r = req("POST", "/Employee/unknownAction");
        inCtx(10L, 42L, () -> {
            assertThatThrownBy(() ->
                    interceptor.preHandle(r, new MockHttpServletResponse(), null))
                    .isInstanceOf(PermissionException.class)
                    .hasMessageContaining("not registered");
            return null;
        });
    }

    // ─── endpoint mapped but user lacks the permission → 403 ───

    @Test
    void missingPermission_throwsPermissionException() {
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(
                PermissionInfo.builder()
                        .roleCodes(Set.of("HR"))
                        .permissions(Set.of("other.view"))
                        .build());
        when(endpointIndex.lookup(eq("/Employee/searchList"), eq("POST")))
                .thenReturn(Set.of("employee.view"));

        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        inCtx(10L, 42L, () -> {
            assertThatThrownBy(() ->
                    interceptor.preHandle(r, new MockHttpServletResponse(), null))
                    .isInstanceOf(PermissionException.class)
                    .hasMessageContaining("Missing permission");
            return null;
        });
    }

    // ─── user has intersecting permission → allow ───

    @Test
    void userHasPermission_allowed() {
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(
                PermissionInfo.builder()
                        .roleCodes(Set.of("HR"))
                        .permissions(Set.of("employee.view", "other.view"))
                        .build());
        when(endpointIndex.lookup(eq("/Employee/searchList"), eq("POST")))
                .thenReturn(Set.of("employee.view"));

        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }

    // ─── shared endpoint reachable via any of multiple permissions ───

    @Test
    void sharedEndpoint_anyPermissionSuffices() {
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(
                PermissionInfo.builder()
                        .roleCodes(Set.of("HR"))
                        .permissions(Set.of("employee.view"))
                        .build());
        when(endpointIndex.lookup(eq("/Department/searchList"), eq("POST")))
                .thenReturn(Set.of("employee.view", "department.view"));

        MockHttpServletRequest r = req("POST", "/Department/searchList");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }

    @Test
    void nullUserPermissions_treatedAsMissing() {
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(
                PermissionInfo.builder().roleCodes(Set.of("HR")).build());   // permissions=null
        when(endpointIndex.lookup(eq("/Employee/searchList"), eq("POST")))
                .thenReturn(Set.of("employee.view"));

        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        inCtx(10L, 42L, () -> {
            assertThatThrownBy(() ->
                    interceptor.preHandle(r, new MockHttpServletResponse(), null))
                    .isInstanceOf(PermissionException.class);
            return null;
        });
    }

    // ─── tenant admin: bypasses the gate, EXCEPT for a module its plan dropped ───
    //
    // A tenant admin holds no static nav grants, so a downgrade has no rows to strip for it and the
    // downgrade cleanup skips it entirely. Its snapshot — already narrowed to the plan by
    // tenantAdminSnapshot — is the only record of what its plan allows, and this branch is the only
    // place that reads it. Without these cases the branch could regress to a blanket `return true`
    // and every test above would still pass.

    /** A tenant admin whose plan dropped payroll: the snapshot carries the surviving permissions only. */
    private static PermissionInfo tenantAdminOnFreePlan() {
        return PermissionInfo.builder()
                .roleCodes(Set.of(PermissionInfo.CODE_TENANT_ADMIN))
                .permissions(Set.of("employee.view", "department.view"))   // no payroll.*
                .build();
    }

    @Test
    void tenantAdmin_deniedOnAModuleThePlanDropped() {
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(tenantAdminOnFreePlan());
        when(endpointIndex.lookup(eq("/PayItem/searchList"), eq("POST")))
                .thenReturn(Set.of("payroll.pay-item.view"));

        MockHttpServletRequest r = req("POST", "/PayItem/searchList");
        inCtx(10L, 42L, () -> {
            assertThatThrownBy(() ->
                    interceptor.preHandle(r, new MockHttpServletResponse(), null))
                    .isInstanceOf(PermissionException.class)
                    .hasMessageContaining("Missing permission");
            return null;
        });
    }

    @Test
    void tenantAdmin_allowedOnAnEntitledModule() {
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(tenantAdminOnFreePlan());
        when(endpointIndex.lookup(eq("/Employee/searchList"), eq("POST")))
                .thenReturn(Set.of("employee.view"));

        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }

    @Test
    void tenantAdmin_stillBypassesAnUnregisteredEndpoint() {
        // The bypass's original job, and the reason the new check is conditional rather than the same
        // code path as a normal user's. Plenty of endpoints carry no permission mapping; a tenant admin
        // is expected to reach them. Denying here would turn a billing gate into a broad outage —
        // note the normal-user path throws "Endpoint not registered" on exactly this input.
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(tenantAdminOnFreePlan());
        when(endpointIndex.lookup(anyString(), anyString())).thenReturn(Set.of());

        MockHttpServletRequest r = req("POST", "/SomeUnmappedThing/doIt");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }

    @Test
    void tenantAdmin_platformOnlyStillDeniedBeforeTheModuleCheck() {
        // Ordering matters: a platform-only endpoint must report itself as platform-only, not as a
        // missing permission — the two send ops to different places.
        props.setPlatformOnlyPatterns(List.of("/TenantInfo/**"));
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(tenantAdminOnFreePlan());

        MockHttpServletRequest r = req("POST", "/TenantInfo/createOne");
        inCtx(10L, 42L, () -> {
            assertThatThrownBy(() ->
                    interceptor.preHandle(r, new MockHttpServletResponse(), null))
                    .isInstanceOf(PermissionException.class)
                    .hasMessageContaining("Platform-admin only");
            return null;
        });
    }

    // ─── consultant: the same gate, reached by a different rule ───
    //
    // A consultant's reach is defined as the tenant's current subscription in full, which is how a
    // tenant admin's is computed too — two rules that agree today, not one rule. They are separately
    // callable on purpose: folding CONSULTANT into isTenantAdmin() would make every other reader of
    // "is a tenant admin" quietly answer yes for consultants, and would leave nothing to change here
    // if consultants were ever narrowed. These cases pin that the second call site exists and behaves,
    // so collapsing it back into the first is a failing test rather than a silent widening.

    /** A consultant in a tenant whose plan dropped payroll. No TENANT_ADMIN code — that is the point. */
    private static PermissionInfo consultantOnFreePlan() {
        return PermissionInfo.builder()
                .roleCodes(Set.of(PermissionInfo.CODE_CONSULTANT))
                .permissions(Set.of("employee.view", "department.view"))   // no payroll.*
                .build();
    }

    @Test
    void consultant_allowedOnAnEntitledModule() {
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(consultantOnFreePlan());
        when(endpointIndex.lookup(eq("/Employee/searchList"), eq("POST")))
                .thenReturn(Set.of("employee.view"));

        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }

    @Test
    void consultant_deniedOnAModuleTheTenantsPlanDropped() {
        // The consultant works inside somebody else's subscription, so the plan bounds them exactly as
        // it bounds that tenant's own admin — being platform staff buys no extra modules.
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(consultantOnFreePlan());
        when(endpointIndex.lookup(eq("/PayItem/searchList"), eq("POST")))
                .thenReturn(Set.of("payroll.pay-item.view"));

        MockHttpServletRequest r = req("POST", "/PayItem/searchList");
        inCtx(10L, 42L, () -> {
            assertThatThrownBy(() ->
                    interceptor.preHandle(r, new MockHttpServletResponse(), null))
                    .isInstanceOf(PermissionException.class)
                    .hasMessageContaining("Missing permission");
            return null;
        });
    }

    @Test
    void consultant_reachesAnUnregisteredEndpointLikeAnAdminDoes() {
        // The asymmetry this branch was added for. Without it a consultant fell through to the normal
        // path and got "Endpoint not registered" wherever the mapping is incomplete — a 403 on work a
        // tenant admin does freely, which contradicts "the tenant's functions, in full".
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(consultantOnFreePlan());
        when(endpointIndex.lookup(anyString(), anyString())).thenReturn(Set.of());

        MockHttpServletRequest r = req("POST", "/SomeUnmappedThing/doIt");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }

    @Test
    void consultant_isStillDeniedPlatformOnlyEndpoints() {
        // The one place being platform staff might have been assumed to help. It does not: the
        // consultant is inside a customer's tenant, and billing / plan / provisioning belong to the
        // platform's own console, which they reach as themselves or not at all.
        props.setPlatformOnlyPatterns(List.of("/TenantInfo/**"));
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(consultantOnFreePlan());

        MockHttpServletRequest r = req("POST", "/TenantInfo/createOne");
        inCtx(10L, 42L, () -> {
            assertThatThrownBy(() ->
                    interceptor.preHandle(r, new MockHttpServletResponse(), null))
                    .isInstanceOf(PermissionException.class)
                    .hasMessageContaining("Platform-admin only");
            return null;
        });
    }

    // ─── a consultant whose authorization ended is out, on the next request ───

    /** Installs a checker that answers the given verdict for every account. */
    private void consultantAccessIs(boolean stillAuthorized) {
        org.springframework.test.util.ReflectionTestUtils.setField(interceptor, "consultantAccessChecker",
                (ConsultantAccessChecker) id -> stillAuthorized);
    }

    @Test
    void consultant_whoseAuthorizationEnded_isRefusedWithItsOwnCode() {
        // Its own response code, not a plain 403: the client's correct reaction is to leave THIS
        // tenant for the picker, where the person's other memberships may still be waiting. Read as
        // "you lack a permission here" it would send them to a tenant admin who cannot grant it.
        consultantAccessIs(false);
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(consultantOnFreePlan());
        when(endpointIndex.lookup(eq("/Employee/searchList"), eq("POST")))
                .thenReturn(Set.of("employee.view"));

        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        inCtx(10L, 42L, () -> {
            assertThatThrownBy(() ->
                    interceptor.preHandle(r, new MockHttpServletResponse(), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("authorization for this tenant has ended");
            return null;
        });
    }

    @Test
    void consultant_stillAuthorized_passesAsBefore() {
        consultantAccessIs(true);
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(consultantOnFreePlan());
        when(endpointIndex.lookup(eq("/Employee/searchList"), eq("POST")))
                .thenReturn(Set.of("employee.view"));

        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }

    @Test
    void lapsedConsultant_canStillReachTheSelfServiceEndpointsThatLetThemRecover() {
        // The check sits after the authenticated-bypass patterns on purpose. A client that has just
        // been told its authorization ended still needs /me and the tenant list to render that state
        // and offer the person's other companies; refusing everything strands them on a blank screen
        // instead of the picker the error is telling them to go back to.
        props.setAuthenticatedBypassPatterns(List.of("/me/**"));
        consultantAccessIs(false);
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(consultantOnFreePlan());

        MockHttpServletRequest r = req("GET", "/me/uiContext");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }

    /** A consultant membership that has ALSO picked up the tenant-admin code — through a role write
     *  the tenant should never have been able to make against a hidden row, but the gate cannot know
     *  how it happened and must not care. */
    private static PermissionInfo consultantWearingTenantAdmin() {
        return PermissionInfo.builder()
                .roleCodes(Set.of("TENANT_ADMIN", PermissionInfo.CODE_CONSULTANT))
                .permissions(Set.of("employee.view"))
                .build();
    }

    @Test
    void consultantWhoAlsoHoldsTenantAdmin_isStillRefusedOnceAuthorizationEnds() {
        // The check sat inside the consultant branch, which is reached only when the caller holds
        // neither admin code. Give a consultant membership TENANT_ADMIN and it took the admin branch
        // first, and the authorization was never checked: disable, revoke and expiry stopped applying to exactly the
        // consultant with the most reach, for as long as the snapshot stayed cached. The question is
        // about the membership, not the bypass earned afterwards, so it is asked before all of them.
        consultantAccessIs(false);
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(consultantWearingTenantAdmin());
        when(endpointIndex.lookup(eq("/Employee/searchList"), eq("POST")))
                .thenReturn(Set.of("employee.view"));

        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        inCtx(10L, 42L, () -> {
            assertThatThrownBy(() ->
                    interceptor.preHandle(r, new MockHttpServletResponse(), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("authorization for this tenant has ended");
            return null;
        });
    }

    @Test
    void consultantWhoAlsoHoldsTenantAdmin_passesWhileAuthorized() {
        // The paired negative: asking about the authorization first must not cost a still-authorized consultant the
        // admin-shaped gate they would otherwise take.
        consultantAccessIs(true);
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(consultantWearingTenantAdmin());
        when(endpointIndex.lookup(eq("/Employee/searchList"), eq("POST")))
                .thenReturn(Set.of("employee.view"));

        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }

    @Test
    void noCheckerInstalled_consultantsAreNotRefused() {
        // A deployment without consultants installs no implementation; the branch must not fail closed
        // on a null collaborator.
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(consultantOnFreePlan());
        when(endpointIndex.lookup(anyString(), anyString())).thenReturn(Set.of());

        MockHttpServletRequest r = req("POST", "/Anything/doIt");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }

    // ─── the one thing that separates the built-in roles at this gate ───

    @Test
    void exactlyOnePrincipalMayReachThePlatformOnlyEndpoints() {
        // The invariant this predicate exists to hold. Billing, plan, provisioning and the
        // consultant models are the platform's own work: the platform administrator is who they
        // exist for, and everyone INSIDE a tenant is barred. Asserted over EVERY BuiltinRole value
        // rather than per principal, so a fourth role added to the framework is barred here by
        // default and its author reads why before deciding otherwise.
        List<BuiltinRole> exempt = new ArrayList<>();
        for (BuiltinRole role : BuiltinRole.values()) {
            if (!PermissionInterceptor.deniedPlatformOnly(role)) {
                exempt.add(role);
            }
        }
        assertThat(exempt)
                .as("only the platform administrator may reach the platform-only endpoints")
                .containsExactly(BuiltinRole.SUPER_ADMIN);
    }

    // ─── the platform admin's shared modules ───

    @Test
    void platformAdmin_reachesASharedModule() {
        // Messaging is in shared-nav-prefixes, not platform-nav-prefixes, and the difference matters:
        // every model under it is multiTenant, so a tenant admin keeps its own menus while the
        // platform reads the same ones on its own tier — where the verification-code and
        // password-reset mails live, including the one a consultant logs in with. Locking the
        // platform out of those was the first version of this narrowing's bug.
        PermissionInfo pi = PermissionInfo.builder()
                .roleCodes(Set.of(PermissionInfo.CODE_SUPER_ADMIN))
                .permissions(Set.of("message.mail-template.view"))
                .build();
        when(snapshotProvider.get(anyLong(), anyLong())).thenReturn(pi);
        when(endpointIndex.lookup(eq("/MailTemplate/searchPage"), eq("POST")))
                .thenReturn(Set.of("message.mail-template.view"));

        MockHttpServletRequest r = req("POST", "/MailTemplate/searchPage");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
    }
}
