package io.softa.starter.permission.flow;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.mock.web.MockHttpServletResponse;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.permission.interceptor.PermissionInterceptor;
import io.softa.starter.permission.interceptor.PermissionInterceptorProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end authorisation flow: request → interceptor → endpoint index
 * → snapshot provider → allow / deny.
 *
 * <p>Real engine beans participate; only the framework boundary
 * ({@link io.softa.framework.orm.service.ModelService},
 * {@link io.softa.framework.orm.service.CacheService},
 * {@link ModelManager} static) is stubbed.
 */
class AuthorizationFlowTest {

    private PermissionFlowFixture fx;

    @BeforeEach
    void setUp() {
        // Reset any request-scoped PermissionInfo a prior test stashed so we
        // always exercise the DB-load path.
        RequestContextHolder.resetRequestAttributes();
        fx = new PermissionFlowFixture();
        // Seed a two-node nav tree with one gated endpoint.
        fx.nav("hr", null, null);
        fx.nav("hr.employee", "Employee", "hr");
        fx.perm("employee.view", "hr.employee", "POST /Employee/searchList");

        // Two roles: HR (has employee.view), Analyst (has nothing).
        fx.role(100L, "HR Manager", null, true);
        fx.role(200L, "Analyst", null, true);
        fx.grant(1L, 100L, "hr.employee", Set.of("employee.view"), null, null);

        // User 42 → HR; user 999 → Analyst; user 500 → unbound.
        fx.bindUserToRole(1L, 42L, 100L);
        fx.bindUserToRole(2L, 999L, 200L);

        fx.wire();
    }

    private static MockHttpServletRequest req(String method, String path) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setServletPath(path);
        return r;
    }

    private PermissionInterceptor buildInterceptor() {
        return new PermissionInterceptor(fx.endpointIndex, fx.provider,
                new PermissionInterceptorProperties());
    }

    // ─── happy path: HR user with the right permission → allowed ───

    @Test
    void hrUser_hitsEmployeeSearchList_allowed() {
        Context ctx = new Context();
        ctx.setTenantId(10L);
        ctx.setUserId(42L);

        Boolean allowed = ContextHolder.callWith(ctx, () -> {
            PermissionInterceptor interceptor = buildInterceptor();
            return interceptor.preHandle(req("POST", "/Employee/searchList"),
                    new MockHttpServletResponse(), null);
        });
        assertThat(allowed).isTrue();
    }

    // ─── Analyst user without the grant → 403 ───

    @Test
    void analystUser_hitsEmployeeSearchList_forbidden() {
        Context ctx = new Context();
        ctx.setTenantId(10L);
        ctx.setUserId(999L);

        ContextHolder.runWith(ctx, () -> {
            PermissionInterceptor interceptor = buildInterceptor();
            assertThatThrownBy(() ->
                    interceptor.preHandle(req("POST", "/Employee/searchList"),
                            new MockHttpServletResponse(), null))
                    .isInstanceOf(PermissionException.class)
                    .hasMessageContaining("Missing permission");
        });
    }

    // ─── Unbound user → interceptor rejects because no user_role_rel row ───

    @Test
    void unboundUser_hitsEmployeeSearchList_forbidden() {
        Context ctx = new Context();
        ctx.setTenantId(10L);
        ctx.setUserId(500L);   // has no user_role_rel

        ContextHolder.runWith(ctx, () -> {
            PermissionInterceptor interceptor = buildInterceptor();
            assertThatThrownBy(() ->
                    interceptor.preHandle(req("POST", "/Employee/searchList"),
                            new MockHttpServletResponse(), null))
                    .isInstanceOf(PermissionException.class);
        });
    }

    // ─── SUPER_ADMIN → the platform's own screens, and not the tenant's (C5) ───
    //
    // Named for what it used to assert: that a super-admin reached a tenant business endpoint with
    // no grant behind it at all. C5 hands that work to the consultant, who does it inside the
    // customer that authorized them and only while the authorization lasts, so the platform
    // administrator is now refused here. Rewritten rather than deleted — the old contract is what
    // must not come back by accident, and this fixture is the one that would notice.

    @Test
    void superAdmin_isRefusedATenantBusinessEndpoint() {
        // Rebuild with a SUPER_ADMIN role bound to user 1.
        PermissionFlowFixture sa = new PermissionFlowFixture();
        sa.nav("hr", null, null);
        sa.nav("hr.employee", "Employee", "hr");
        sa.perm("employee.view", "hr.employee", "POST /Employee/searchList");
        sa.role(1L, "Super Admin", "SUPER_ADMIN", true);
        // No grants, no permissions — SUPER_ADMIN short-circuits.
        sa.bindUserToRole(1L, 1L, 1L);
        sa.wire();

        Context ctx = new Context();
        ctx.setTenantId(10L);
        ctx.setUserId(1L);

        ContextHolder.runWith(ctx, () -> {
            PermissionInterceptor interceptor = new PermissionInterceptor(
                    sa.endpointIndex, sa.provider, new PermissionInterceptorProperties());
            // /Employee/searchList is a tenant business endpoint and IS mapped, so the gate has a
            // permission to match against — and the platform administrator's snapshot does not hold
            // it. An unmapped endpoint would still pass, which is the allowance, not the boundary.
            assertThatThrownBy(() ->
                    interceptor.preHandle(req("POST", "/Employee/searchList"),
                            new MockHttpServletResponse(), null))
                    .isInstanceOf(PermissionException.class);
        });
    }

    // ─── unmapped endpoint → 403 for everyone (except the admin-shaped principals) ───

    @Test
    void hrUser_hitsUnmappedEndpoint_forbidden() {
        Context ctx = new Context();
        ctx.setTenantId(10L);
        ctx.setUserId(42L);

        ContextHolder.runWith(ctx, () -> {
            PermissionInterceptor interceptor = buildInterceptor();
            assertThatThrownBy(() ->
                    interceptor.preHandle(req("POST", "/Employee/somethingElse"),
                            new MockHttpServletResponse(), null))
                    .isInstanceOf(PermissionException.class)
                    .hasMessageContaining("not registered");
        });
    }

    // ─── public URI patterns → allowed without auth ───

    @Test
    void publicUri_allowedWithoutAuth() {
        PermissionInterceptorProperties props = new PermissionInterceptorProperties();
        props.setPublicUriPatterns(List.of("/auth/**"));
        PermissionInterceptor interceptor = new PermissionInterceptor(
                fx.endpointIndex, fx.provider, props);

        boolean allowed = interceptor.preHandle(req("POST", "/auth/login"),
                new MockHttpServletResponse(), null);
        assertThat(allowed).isTrue();
    }
}
