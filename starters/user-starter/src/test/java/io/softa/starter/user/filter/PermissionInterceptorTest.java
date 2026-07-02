package io.softa.starter.user.filter;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.ConfigurationException;
import io.softa.framework.base.exception.PermissionException;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.dto.PermissionInfo;
import io.softa.starter.user.service.EndpointIndex;
import io.softa.starter.user.service.PermissionInfoEnricher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionInterceptorTest {

    private EndpointIndex endpointIndex;
    private PermissionInfoEnricher enricher;
    private PermissionInterceptorProperties props;
    private PermissionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        endpointIndex = mock(EndpointIndex.class);
        enricher = mock(PermissionInfoEnricher.class);
        props = new PermissionInterceptorProperties();
        interceptor = new PermissionInterceptor(endpointIndex, enricher, props);
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
        // Never called EndpointIndex/enricher for this bypass path.
        org.mockito.Mockito.verify(endpointIndex, org.mockito.Mockito.never())
                .lookup(anyString(), anyString());
    }

    // ─── super-admin short-circuit ───

    @Test
    void superAdmin_bypassesEndpointCheck() {
        PermissionInfo pi = PermissionInfo.builder()
                .roleCodes(Set.of(RoleConstant.CODE_SUPER_ADMIN))
                .build();
        when(enricher.enrich(eq(10L), eq(42L))).thenReturn(pi);

        MockHttpServletRequest r = req("POST", "/Employee/searchList");
        boolean allowed = inCtx(10L, 42L,
                () -> interceptor.preHandle(r, new MockHttpServletResponse(), null));
        assertThat(allowed).isTrue();
        org.mockito.Mockito.verify(endpointIndex, org.mockito.Mockito.never())
                .lookup(anyString(), anyString());
    }

    // ─── unmapped endpoint → 403 ───

    @Test
    void unmappedEndpoint_throwsPermissionException() {
        when(enricher.enrich(anyLong(), anyLong())).thenReturn(
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
        when(enricher.enrich(anyLong(), anyLong())).thenReturn(
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
        when(enricher.enrich(anyLong(), anyLong())).thenReturn(
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
        when(enricher.enrich(anyLong(), anyLong())).thenReturn(
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
        when(enricher.enrich(anyLong(), anyLong())).thenReturn(
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
}
