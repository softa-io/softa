package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import io.softa.starter.user.enums.UserRoleSource;
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
        // Every account the tests name is Active unless a test says otherwise.
        activeAccounts(1L, 2L, 5L);
    }

    /** The accounts the UserAccount lookup reports as Active. */
    @SuppressWarnings("unchecked")
    private void activeAccounts(Long... ids) {
        List<java.util.Map<String, Object>> rows = java.util.Arrays.stream(ids)
                .map(id -> java.util.Map.<String, Object>of("id", id)).toList();
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("UserAccount"),
                any(io.softa.framework.orm.domain.FlexQuery.class))).thenReturn(rows);
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

    // ─── syncRoleInternal (delete + insert transaction body) ───

    @Test
    void syncRoleInternal_nullDynamicFilter_wipesButInsertsNothing() {
        Role role = new Role();
        role.setId(500L);
        role.setDynamicFilter(null);   // rule cleared — expect wipe only
        when(roleService.getById(500L)).thenReturn(Optional.of(role));
        when(userRoleRelService.searchList(any(Filters.class))).thenReturn(List.of());
        when(userRoleRelService.deleteByFilters(any())).thenReturn(true);

        int count = ContextHolder.callWith(ctx(10L), () -> job.syncRole(500L));

        assertThat(count).isZero();
        // Wipe still ran once.
        verify(userRoleRelService, times(1)).deleteByFilters(any());
        verify(userRoleRelService, never()).createList(any());
    }

    @Test
    void syncRoleInternal_dynamicFilterMatchesEmployees_insertsDynamicRows() {
        Role role = roleWithDynamic(500L, "OnCall");
        when(roleService.getById(500L)).thenReturn(Optional.of(role));
        when(userRoleRelService.searchList(any(Filters.class))).thenReturn(List.of());
        when(userRoleRelService.deleteByFilters(any())).thenReturn(true);
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("Employee"),
                any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of(
                        java.util.Map.of("userId", 1L),
                        java.util.Map.of("userId", 2L)));

        int count = ContextHolder.callWith(ctx(10L), () -> job.syncRole(500L));

        assertThat(count).isEqualTo(2);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<io.softa.starter.user.entity.UserRoleRel>> cap =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(userRoleRelService).createList(cap.capture());
        assertThat(cap.getValue()).hasSize(2)
                .allSatisfy(r -> {
                    assertThat(r.getRoleId()).isEqualTo(500L);
                    assertThat(r.getSource()).isEqualTo(
                            UserRoleSource.DYNAMIC);
                });
    }

    @Test
    void syncRole_writesTheMembershipAsASystemWrite_butLooksEmployeesUpAsBefore() {
        // The rule was checked when the role was saved; the cron or consumer running it has nobody
        // for "may this caller assign the role" to ask about. Only the write is lifted.
        Role role = roleWithDynamic(500L, "OnCall");
        when(roleService.getById(500L)).thenReturn(Optional.of(role));
        when(userRoleRelService.searchList(any(Filters.class))).thenReturn(List.of());
        when(userRoleRelService.deleteByFilters(any())).thenReturn(true);
        boolean[] skippedAtLookup = {true};
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("Employee"),
                any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenAnswer(inv -> {
                    skippedAtLookup[0] = ContextHolder.getContext().isSkipPermissionCheck();
                    return List.of(java.util.Map.of("userId", 1L));
                });
        boolean[] skippedAtWrite = {false};
        when(userRoleRelService.createList(any())).thenAnswer(inv -> {
            skippedAtWrite[0] = ContextHolder.getContext().isSkipPermissionCheck();
            return List.of(1L);
        });

        ContextHolder.callWith(ctx(10L), () -> job.syncRole(500L));

        assertThat(skippedAtLookup[0]).isFalse();
        assertThat(skippedAtWrite[0]).isTrue();
    }

    @Test
    void syncRoleInternal_userWithManualRow_skippedFromDynamicInsert() {
        // Manual takes precedence — user 1 has a MANUAL row on this role,
        // so even though the dynamic filter matches them, no DYNAMIC row
        // is written for them.
        Role role = roleWithDynamic(500L, "OnCall");
        when(roleService.getById(500L)).thenReturn(Optional.of(role));
        io.softa.starter.user.entity.UserRoleRel manual = new io.softa.starter.user.entity.UserRoleRel();
        manual.setUserId(1L);
        when(userRoleRelService.searchList(any(Filters.class))).thenReturn(List.of(manual));
        when(userRoleRelService.deleteByFilters(any())).thenReturn(true);
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("Employee"),
                any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of(
                        java.util.Map.of("userId", 1L),
                        java.util.Map.of("userId", 2L)));

        int count = ContextHolder.callWith(ctx(10L), () -> job.syncRole(500L));

        // 2 matched, 1 (manual) skipped → 1 dynamic row inserted.
        assertThat(count).isEqualTo(1);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<io.softa.starter.user.entity.UserRoleRel>> cap =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(userRoleRelService).createList(cap.capture());
        assertThat(cap.getValue().getFirst().getUserId()).isEqualTo(2L);
    }

    @Test
    void syncRoleInternal_noMatchedEmployees_wipesWithoutInsert() {
        Role role = roleWithDynamic(500L, "Empty");
        when(roleService.getById(500L)).thenReturn(Optional.of(role));
        when(userRoleRelService.searchList(any(Filters.class))).thenReturn(List.of());
        when(userRoleRelService.deleteByFilters(any())).thenReturn(true);
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("Employee"),
                any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of());   // no matches

        int count = ContextHolder.callWith(ctx(10L), () -> job.syncRole(500L));

        assertThat(count).isZero();
        verify(userRoleRelService).deleteByFilters(any());   // wipe happened
        verify(userRoleRelService, never()).createList(any());
    }

    @Test
    void syncRoleInternal_employeeWithNullUserId_skipped() {
        Role role = roleWithDynamic(500L, "PureEmployeeFilter");
        when(roleService.getById(500L)).thenReturn(Optional.of(role));
        when(userRoleRelService.searchList(any(Filters.class))).thenReturn(List.of());
        when(userRoleRelService.deleteByFilters(any())).thenReturn(true);
        // Pure-employee row has userId == null.
        java.util.Map<String, Object> pureEmployee = new java.util.HashMap<>();
        pureEmployee.put("userId", null);
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("Employee"),
                any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of(pureEmployee, java.util.Map.of("userId", 5L)));

        int count = ContextHolder.callWith(ctx(10L), () -> job.syncRole(500L));

        assertThat(count).isEqualTo(1);   // pure-employee dropped, active one kept
    }

    @Test
    void syncRoleInternal_inactiveAccount_getsNoDynamicRow() {
        Role role = roleWithDynamic(500L, "OnCall");
        when(roleService.getById(500L)).thenReturn(Optional.of(role));
        when(userRoleRelService.searchList(any(Filters.class))).thenReturn(List.of());
        when(userRoleRelService.deleteByFilters(any())).thenReturn(true);
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("Employee"),
                any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of(
                        java.util.Map.of("userId", 1L),
                        java.util.Map.of("userId", 2L)));
        activeAccounts(2L);   // account 1 is Locked, Frozen or not yet verified

        int count = ContextHolder.callWith(ctx(10L), () -> job.syncRole(500L));

        assertThat(count).isEqualTo(1);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<io.softa.starter.user.entity.UserRoleRel>> cap =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(userRoleRelService).createList(cap.capture());
        assertThat(cap.getValue()).extracting(io.softa.starter.user.entity.UserRoleRel::getUserId)
                .containsExactly(2L);
    }

    @Test
    void syncRoleInternal_asksTheAccountForItsStatus_withoutPathingThroughTheEmployee() {
        // Employee.userId is a plain id: a "userId.status" filter on Employee cannot resolve. The
        // account's status is read from the account, past the caller's permissions — whoever saved
        // the role need not hold the account read.
        Role role = roleWithDynamic(500L, "OnCall");
        when(roleService.getById(500L)).thenReturn(Optional.of(role));
        when(userRoleRelService.searchList(any(Filters.class))).thenReturn(List.of());
        when(userRoleRelService.deleteByFilters(any())).thenReturn(true);
        org.mockito.ArgumentCaptor<io.softa.framework.orm.domain.FlexQuery> employeeQuery =
                org.mockito.ArgumentCaptor.forClass(io.softa.framework.orm.domain.FlexQuery.class);
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("Employee"), employeeQuery.capture()))
                .thenReturn(List.of(java.util.Map.of("userId", 1L)));
        boolean[] skippedAtAccount = {false};
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("UserAccount"),
                any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenAnswer(inv -> {
                    skippedAtAccount[0] = ContextHolder.getContext().isSkipPermissionCheck();
                    return List.of(java.util.Map.of("id", 1L));
                });

        ContextHolder.callWith(ctx(10L), () -> job.syncRole(500L));

        assertThat(employeeQuery.getValue().getFilters().toString()).doesNotContain("userId.status");
        assertThat(skippedAtAccount[0]).isTrue();
    }

    @Test
    void syncMembershipForUser_inactiveAccount_isNotGranted() {
        Role role = roleWithDynamic(500L, "OnCall");
        when(roleService.searchList(any(Filters.class))).thenReturn(List.of(role));
        when(modelService.count(org.mockito.ArgumentMatchers.eq("Employee"), any(Filters.class))).thenReturn(1L);
        activeAccounts();   // the account matched the rule but is not Active

        ContextHolder.runWith(ctx(10L), () -> job.syncMembershipForUser(10L, 7L));

        verify(userRoleRelService, never()).createOne(any());
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
        // dynamicFilter must be a non-empty ARRAY (Filters tuple form). The
        // impl checks `rule.isArray()` and Filters.of(list) returns null for
        // an empty list — so at least one leaf must be present.
        tools.jackson.databind.node.ArrayNode leaf =
                tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        leaf.add("status").add("=").add("Active");
        r.setDynamicFilter(leaf);
        return r;
    }
}
