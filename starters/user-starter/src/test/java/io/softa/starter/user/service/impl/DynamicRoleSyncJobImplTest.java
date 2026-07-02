package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserRoleRelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicRoleSyncJobImplTest {

    private RoleService roleService;
    private UserRoleRelService userRoleRelService;
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ModelService modelService;
    private PlatformTransactionManager tm;
    private DynamicRoleSyncJobImpl job;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @BeforeEach
    void setUp() {
        roleService = mock(RoleService.class);
        userRoleRelService = mock(UserRoleRelService.class);
        modelService = mock(ModelService.class);
        tm = mock(PlatformTransactionManager.class);
        job = new DynamicRoleSyncJobImpl(roleService, userRoleRelService, modelService, tm) {
            // Force the TransactionTemplate to inline-execute (no real tx manager).
            {
                org.springframework.test.util.ReflectionTestUtils.setField(
                        this, "transactionTemplate", inlineTemplate(tm));
            }
        };
    }

    private static TransactionTemplate inlineTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm) {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    // ─── syncRole ───

    @Test
    void syncRole_nullRoleId_returnsZero() {
        // Must run inside tenant context so assertActiveTenantContext(...) sees it,
        // but syncRole returns 0 early before reaching that check.
        assertThat(job.syncRole(null)).isZero();
        verify(roleService, never()).getById(any());
    }

    @Test
    void syncRole_missingTenantContext_throws() {
        // With no tenant, the assert fails-loud so cron misconfiguration surfaces.
        assertThatThrownBy(() -> job.syncRole(500L))
                .hasMessageContaining("requires an active tenant context");
    }

    @Test
    void syncRole_roleNotFound_returnsZero() {
        when(roleService.getById(500L)).thenReturn(Optional.empty());
        Integer out = ContextHolder.callWith(ctx(10L), () -> job.syncRole(500L));
        assertThat(out).isZero();
    }

    // ─── syncAll ───

    @Test
    void syncAll_missingTenantContext_throws() {
        assertThatThrownBy(() -> job.syncAll())
                .hasMessageContaining("requires an active tenant context");
    }

    @Test
    void syncAll_noDynamicRoles_completesWithoutTouchingUserRoleRel() {
        when(roleService.searchList(any(Filters.class))).thenReturn(List.of());
        ContextHolder.runWith(ctx(10L), () -> job.syncAll());
        verify(userRoleRelService, never()).deleteByFilters(any());
    }

    @Test
    void syncAll_iteratesEveryDynamicRole_continuingOnPerRoleError() {
        Role r1 = roleWithDynamic(1L, "r1");
        Role r2 = roleWithDynamic(2L, "r2");
        when(roleService.searchList(any(Filters.class))).thenReturn(List.of(r1, r2));

        // r1 throws inside its sync; r2 must still run.
        when(userRoleRelService.deleteByFilters(any()))
                .thenThrow(new RuntimeException("db blip on r1"))
                .thenReturn(true);   // r2's delete-then-insert succeeds
        when(modelService.searchList(any(String.class), any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of());  // no members found

        ContextHolder.runWith(ctx(10L), () -> job.syncAll());

        // Both roles attempted deleteByFilters — the loop continues on r1's error.
        verify(userRoleRelService, times(2)).deleteByFilters(any());
    }

    // ─── syncMembershipForUser ───

    @Test
    void syncMembershipForUser_nullTenantId_throws() {
        assertThatThrownBy(() -> job.syncMembershipForUser(null, 42L))
                .hasMessageContaining("explicit tenantId");
    }

    @Test
    void syncMembershipForUser_nullUserId_returnsZero() {
        assertThat(job.syncMembershipForUser(10L, null)).isZero();
    }

    @Test
    void syncMembershipForUser_forcesTenantContext_evenIfCallerHasNone() {
        // Call from outside any tenant context — the impl must still succeed
        // because it builds a fresh Context around the internal work.
        when(roleService.searchList(any(Filters.class))).thenReturn(List.of());
        int out = job.syncMembershipForUser(10L, 42L);
        assertThat(out).isZero();
    }

    // ─── helpers ───

    private static Context ctx(Long tenantId) {
        Context c = new Context();
        c.setTenantId(tenantId);
        c.setUserId(1L);
        return c;
    }

    private static Role roleWithDynamic(Long id, String name) {
        Role r = new Role();
        r.setId(id);
        r.setName(name);
        r.setDynamicFilter(tools.jackson.databind.node.JsonNodeFactory.instance
                .objectNode().put("field", "status").put("value", "Active"));
        return r;
    }
}
