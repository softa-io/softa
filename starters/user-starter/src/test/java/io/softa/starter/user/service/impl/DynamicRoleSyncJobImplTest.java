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
    private io.softa.framework.orm.service.PermissionService permissionService;
    private DynamicRoleSyncJobImpl job;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @BeforeEach
    void setUp() {
        roleService = mock(RoleService.class);
        userRoleRelService = mock(UserRoleRelService.class);
        modelService = mock(ModelService.class);
        tm = mock(PlatformTransactionManager.class);
        permissionService = mock(io.softa.framework.orm.service.PermissionService.class);
        when(permissionService.conditionFieldsNotSeenEverywhere(any(), any())).thenReturn(java.util.Set.of());
        org.springframework.beans.factory.ObjectProvider<io.softa.framework.orm.service.PermissionService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(permissionService);
        job = new DynamicRoleSyncJobImpl(roleService, userRoleRelService, modelService, provider, tm) {
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
                        java.util.Map.of("id", 101L, "userId", 1L),
                        java.util.Map.of("id", 102L, "userId", 2L)));

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
    void syncRole_matchesTheRuleTenantWide_andWritesAsASystemWrite() {
        // Who the rule grants is not the saver's data scope to narrow: an HR scoped to a few
        // departments must not take the role away from everyone outside them. The tenant stays.
        Role role = roleWithDynamic(500L, "OnCall");
        when(roleService.getById(500L)).thenReturn(Optional.of(role));
        when(userRoleRelService.searchList(any(Filters.class))).thenReturn(List.of());
        when(userRoleRelService.deleteByFilters(any())).thenReturn(true);
        boolean[] skippedAtLookup = {false};
        Long[] tenantAtLookup = {null};
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("Employee"),
                any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenAnswer(inv -> {
                    skippedAtLookup[0] = ContextHolder.getContext().isSkipPermissionCheck();
                    tenantAtLookup[0] = ContextHolder.getContext().getTenantId();
                    return List.of(java.util.Map.of("id", 101L, "userId", 1L));
                });
        boolean[] skippedAtWrite = {false};
        when(userRoleRelService.createList(any())).thenAnswer(inv -> {
            skippedAtWrite[0] = ContextHolder.getContext().isSkipPermissionCheck();
            return List.of(1L);
        });

        ContextHolder.callWith(ctx(10L), () -> job.syncRole(500L));

        assertThat(skippedAtLookup[0]).isTrue();
        assertThat(tenantAtLookup[0]).isEqualTo(10L);
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
                        java.util.Map.of("id", 101L, "userId", 1L),
                        java.util.Map.of("id", 102L, "userId", 2L)));

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
        pureEmployee.put("id", 199L);
        pureEmployee.put("userId", null);
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("Employee"),
                any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of(pureEmployee, java.util.Map.of("id", 105L, "userId", 5L)));

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
                        java.util.Map.of("id", 101L, "userId", 1L),
                        java.util.Map.of("id", 102L, "userId", 2L)));
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
                .thenReturn(List.of(java.util.Map.of("id", 101L, "userId", 1L)));
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

    // ─── previewMembers ───

    @Test
    void previewMembers_countsEveryMember_listsOnlyThoseTheCallerSees() {
        // Employees 101, 102, 105, 106 match the rule; account 2 is not Active, so 102 is not a
        // member. The caller's scope reaches 101 and 105, not 106.
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("Employee"),
                any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenAnswer(inv -> {
                    io.softa.framework.orm.domain.FlexQuery q = inv.getArgument(1);
                    if (ContextHolder.getContext().isSkipPermissionCheck()) {
                        return List.of(
                                java.util.Map.of("id", 101L, "userId", 1L),
                                java.util.Map.of("id", 102L, "userId", 2L),
                                java.util.Map.of("id", 105L, "userId", 5L),
                                java.util.Map.of("id", 106L, "userId", 6L));
                    }
                    if (q.getFields().contains("code")) {
                        return List.of(java.util.Map.of("id", 105L, "code", "E5"));
                    }
                    return List.of(java.util.Map.of("id", 105L), java.util.Map.of("id", 101L));
                });
        activeAccounts(1L, 5L, 6L);

        io.softa.starter.user.dto.DynamicMembersPreview preview = ContextHolder.callWith(ctx(10L),
                () -> job.previewMembers(roleWithDynamic(500L, "OnCall").getDynamicFilter(), List.of("code"), 2, 1));

        assertThat(preview.memberCount()).isEqualTo(3);
        assertThat(preview.outsideScopeCount()).isEqualTo(1);
        assertThat(preview.visible().getTotalCount()).isEqualTo(2);
        // Member order (101 then 105), not the order the visibility read came back in.
        assertThat(preview.visible().getRows()).extracting(row -> row.get("id")).containsExactly(105L);
        verify(userRoleRelService, never()).createList(any());
        verify(userRoleRelService, never()).deleteByFilters(any());
    }

    @Test
    void previewMembers_ruleOnAFieldTheCallerCannotSeeEverywhere_isRefused() {
        when(permissionService.conditionFieldsNotSeenEverywhere(org.mockito.ArgumentMatchers.eq("Employee"), any()))
                .thenReturn(java.util.Set.of("basePay"));

        assertThatThrownBy(() -> ContextHolder.callWith(ctx(10L),
                () -> job.previewMembers(roleWithDynamic(500L, "OnCall").getDynamicFilter(), List.of("code"), 1, 20)))
                .isInstanceOf(io.softa.framework.orm.service.validation.WriteValidationException.class)
                .satisfies(e -> assertThat(((io.softa.framework.orm.service.validation.WriteValidationException) e)
                        .fieldErrors()).containsKey("basePay"));
        // Refused before any employee is matched: the refusal says nothing about the data.
        verify(modelService, never()).searchList(org.mockito.ArgumentMatchers.eq("Employee"),
                any(io.softa.framework.orm.domain.FlexQuery.class));
    }

    @Test
    void previewMembers_callerWhoMayNotReadEmployees_seesTheCountsAndNobodyListed() {
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("Employee"),
                any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenAnswer(inv -> {
                    if (ContextHolder.getContext().isSkipPermissionCheck()) {
                        return List.of(java.util.Map.of("id", 101L, "userId", 1L),
                                java.util.Map.of("id", 105L, "userId", 5L));
                    }
                    throw new io.softa.framework.base.exception.PermissionException("No read on Employee");
                });

        io.softa.starter.user.dto.DynamicMembersPreview preview = ContextHolder.callWith(ctx(10L),
                () -> job.previewMembers(roleWithDynamic(500L, "OnCall").getDynamicFilter(), List.of("code"), 1, 20));

        assertThat(preview.memberCount()).isEqualTo(2);
        assertThat(preview.outsideScopeCount()).isEqualTo(2);
        assertThat(preview.visible().getRows()).isEmpty();
    }

    @Test
    void previewMembers_matchesInEmployeeIdOrder_notByAnAppField() {
        java.util.List<io.softa.framework.orm.domain.FlexQuery> matched = new java.util.ArrayList<>();
        when(modelService.searchList(org.mockito.ArgumentMatchers.eq("Employee"),
                any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenAnswer(inv -> {
                    if (ContextHolder.getContext().isSkipPermissionCheck()) matched.add(inv.getArgument(1));
                    return List.of();
                });

        ContextHolder.callWith(ctx(10L),
                () -> job.previewMembers(roleWithDynamic(500L, "OnCall").getDynamicFilter(), List.of("code"), 1, 20));

        assertThat(matched).hasSize(1);
        assertThat(matched.getFirst().getOrders().toString()).contains("id").doesNotContain("code");
    }

    @Test
    void previewMembers_noRule_matchesNobody() {
        io.softa.starter.user.dto.DynamicMembersPreview preview = ContextHolder.callWith(ctx(10L),
                () -> job.previewMembers(null, List.of("code"), 1, 20));

        assertThat(preview.memberCount()).isZero();
        assertThat(preview.outsideScopeCount()).isZero();
        assertThat(preview.visible().getRows()).isEmpty();
        verify(modelService, never()).searchList(org.mockito.ArgumentMatchers.eq("Employee"),
                any(io.softa.framework.orm.domain.FlexQuery.class));
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
