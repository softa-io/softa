package io.softa.starter.user.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.dto.PermissionInfo;
import io.softa.starter.user.dto.Principal;
import io.softa.starter.user.filter.SensitiveFieldSetCache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionInfoEnricherTest {

    private RoleService roleService;
    private UserRoleRelService userRoleRelService;
    private RoleNavigationService roleNavigationService;
    private NavigationModelResolver navResolver;
    private SensitiveFieldSetCache sfsCache;
    private CacheService cacheService;
    private PermissionInfoEnricher enricher;

    @BeforeEach
    void setUp() {
        roleService = mock(RoleService.class);
        userRoleRelService = mock(UserRoleRelService.class);
        roleNavigationService = mock(RoleNavigationService.class);
        navResolver = mock(NavigationModelResolver.class);
        sfsCache = mock(SensitiveFieldSetCache.class);
        cacheService = mock(CacheService.class);
        enricher = new PermissionInfoEnricher(
                roleService, userRoleRelService, roleNavigationService,
                navResolver, sfsCache, List.of(), cacheService);
        RequestContextHolder.resetRequestAttributes();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // RequestContextHolder is thread-local — leaking a stashed PermissionInfo
        // into subsequent tests (esp. the flow tests) causes false positives.
        RequestContextHolder.resetRequestAttributes();
    }

    // ─── cacheKey shape ───

    @Test
    void cacheKey_shape() {
        assertThat(PermissionInfoEnricher.cacheKey(10L, 42L))
                .isEqualTo("perm:10:user:42");
    }

    @Test
    void cacheKey_nullTenant_stillProducesKey() {
        assertThat(PermissionInfoEnricher.cacheKey(null, 42L))
                .isEqualTo("perm:null:user:42");
    }

    // ─── cache tier 1 (request-scoped) ───

    @Test
    void enrich_requestScopedHit_returnsStashedInstanceWithoutRedis() {
        String key = PermissionInfoEnricher.cacheKey(10L, 42L);
        PermissionInfo stashed = PermissionInfo.builder()
                .principal(Principal.builder().userId(42L).build())
                .roleCodes(java.util.Set.of("HR"))
                .build();

        // Bind a real ServletRequestAttributes so RequestContextHolder returns it.
        MockHttpServletRequest req = new MockHttpServletRequest();
        ServletRequestAttributes attrs = new ServletRequestAttributes(req);
        RequestContextHolder.setRequestAttributes(attrs);
        attrs.setAttribute(key, stashed, RequestAttributes.SCOPE_REQUEST);

        PermissionInfo out = enricher.enrich(10L, 42L);
        assertThat(out).isSameAs(stashed);
        verify(cacheService, never()).get(anyString(), eq(PermissionInfo.class));
    }

    // ─── cache tier 2 (Redis) ───

    @Test
    void enrich_redisHit_stashesIntoRequestAndReturns() {
        String key = PermissionInfoEnricher.cacheKey(10L, 42L);
        PermissionInfo fromRedis = PermissionInfo.builder()
                .principal(Principal.builder().userId(42L).build())
                .roleCodes(java.util.Set.of("SUPER_ADMIN"))
                .build();

        MockHttpServletRequest req = new MockHttpServletRequest();
        ServletRequestAttributes attrs = new ServletRequestAttributes(req);
        RequestContextHolder.setRequestAttributes(attrs);

        when(cacheService.get(eq(key), eq(PermissionInfo.class))).thenReturn(fromRedis);

        PermissionInfo out = enricher.enrich(10L, 42L);
        assertThat(out).isSameAs(fromRedis);
        assertThat(attrs.getAttribute(key, RequestAttributes.SCOPE_REQUEST)).isSameAs(fromRedis);
    }

    @Test
    void enrich_redisReadFailure_fallsThroughToDbLoad() {
        when(cacheService.get(anyString(), eq(PermissionInfo.class)))
                .thenThrow(new RuntimeException("redis blip"));
        when(userRoleRelService.searchList(org.mockito.ArgumentMatchers.any(
                io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of());

        PermissionInfo out = enricher.enrich(10L, 42L);
        // No throw; DB load produced a fresh (empty-grant) PermissionInfo.
        assertThat(out).isNotNull();
        assertThat(out.getPrincipal().getUserId()).isEqualTo(42L);
    }

    // ─── SUPER_ADMIN short-circuit inside DB load ───

    @Test
    void enrich_userHoldsSuperAdmin_returnsShortCircuitSnapshot() {
        // User → role rel returns a role id 1.
        io.softa.starter.user.entity.UserRoleRel rel = new io.softa.starter.user.entity.UserRoleRel();
        rel.setRoleId(1L);
        when(userRoleRelService.searchList(org.mockito.ArgumentMatchers.any(
                io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of(rel));

        io.softa.starter.user.entity.Role sa = new io.softa.starter.user.entity.Role();
        sa.setId(1L);
        sa.setCode(RoleConstant.CODE_SUPER_ADMIN);
        sa.setActive(true);
        when(roleService.searchList(org.mockito.ArgumentMatchers.any(
                io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of(sa));

        PermissionInfo out = enricher.enrich(10L, 42L);
        assertThat(out.isSuperAdmin()).isTrue();
        assertThat(out.getPermissions()).isNullOrEmpty();
        assertThat(out.getNavigations()).isNullOrEmpty();
        // No role-navigation lookup — SUPER_ADMIN short-circuits.
        verify(roleNavigationService, never()).searchList(
                org.mockito.ArgumentMatchers.any(io.softa.framework.orm.domain.FlexQuery.class));
    }

    // ─── cache write back on DB miss ───

    @Test
    void enrich_dbLoad_writesResultToRedis() {
        when(cacheService.get(anyString(), eq(PermissionInfo.class))).thenReturn(null);
        when(userRoleRelService.searchList(org.mockito.ArgumentMatchers.any(
                io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of());

        String expectedKey = PermissionInfoEnricher.cacheKey(10L, 42L);
        PermissionInfo out = enricher.enrich(10L, 42L);

        assertThat(out).isNotNull();
        verify(cacheService).save(eq(expectedKey), eq(out), anyInt());
    }

    @Test
    void enrich_cacheWriteFailure_stillReturnsFresh() {
        when(cacheService.get(anyString(), eq(PermissionInfo.class))).thenReturn(null);
        when(userRoleRelService.searchList(org.mockito.ArgumentMatchers.any(
                io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("redis write failed"))
                .when(cacheService).save(anyString(), org.mockito.ArgumentMatchers.any(PermissionInfo.class), anyInt());

        PermissionInfo out = enricher.enrich(10L, 42L);
        assertThat(out).isNotNull();   // no propagation
    }
}
