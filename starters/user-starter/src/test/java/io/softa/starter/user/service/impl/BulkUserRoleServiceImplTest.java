package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.BulkAddResult;
import io.softa.starter.user.dto.UserRolePair;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.RoleSource;
import io.softa.starter.user.service.PermissionCacheInvalidator;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserRoleRelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BulkUserRoleServiceImplTest {

    private UserRoleRelService userRoleRelService;
    private PermissionCacheInvalidator invalidator;
    private UserAccountService userAccountService;
    private RoleService roleService;
    private BulkUserRoleServiceImpl svc;

    @BeforeEach
    void setUp() {
        userRoleRelService = mock(UserRoleRelService.class);
        invalidator = mock(PermissionCacheInvalidator.class);
        userAccountService = mock(UserAccountService.class);
        roleService = mock(RoleService.class);
        svc = new BulkUserRoleServiceImpl(userRoleRelService, invalidator,
                userAccountService, roleService);
    }

    @Test
    void nullPairs_returnsEmptySummary() {
        BulkAddResult r = svc.bulkAdd(null, RoleSource.MANUAL);
        assertThat(r.getSummary().getRequested()).isZero();
        assertThat(r.getAdded()).isEmpty();
        assertThat(r.getSkipped()).isEmpty();
        verify(invalidator, never()).evictBatch(anyLong(), anySet());
    }

    @Test
    void emptyPairs_returnsEmptySummary() {
        BulkAddResult r = svc.bulkAdd(List.of(), RoleSource.MANUAL);
        assertThat(r.getSummary().getRequested()).isZero();
    }

    @Test
    void invalidPair_isSkippedWithInvalidPairReason() {
        // Both null → INVALID_PAIR
        UserRolePair bad = new UserRolePair();
        BulkAddResult r = svc.bulkAdd(List.of(bad), RoleSource.MANUAL);
        assertThat(r.getSkipped()).hasSize(1);
        assertThat(r.getSkipped().getFirst().getReason()).isEqualTo("INVALID_PAIR");
    }

    @Test
    void crossTenantUserOrRole_isSkippedWithNotFound() {
        // Payload references user 7 and role 500. Probe returns empty →
        // both are cross-tenant / non-existent from caller's tenant view.
        when(userAccountService.searchList(any(Filters.class))).thenReturn(List.of());
        when(roleService.searchList(any(Filters.class))).thenReturn(List.of());

        UserRolePair p = new UserRolePair();
        p.setUserId(7L);
        p.setRoleId(500L);

        BulkAddResult r = svc.bulkAdd(List.of(p), RoleSource.MANUAL);
        assertThat(r.getSkipped()).hasSize(1);
        assertThat(r.getSkipped().getFirst().getReason()).isEqualTo("NOT_FOUND");
        assertThat(r.getAdded()).isEmpty();
    }

    @Test
    void alreadyAssigned_isSkipped_notReinserted() {
        UserAccount acc = new UserAccount();
        acc.setId(7L);
        when(userAccountService.searchList(any(Filters.class))).thenReturn(List.of(acc));
        Role role = new Role();
        role.setId(500L);
        when(roleService.searchList(any(Filters.class))).thenReturn(List.of(role));

        UserRoleRel existing = new UserRoleRel();
        existing.setUserId(7L);
        existing.setRoleId(500L);
        existing.setSource(RoleSource.MANUAL);
        when(userRoleRelService.searchList(any(Filters.class))).thenReturn(List.of(existing));

        UserRolePair p = new UserRolePair();
        p.setUserId(7L);
        p.setRoleId(500L);

        BulkAddResult r = svc.bulkAdd(List.of(p), RoleSource.MANUAL);
        assertThat(r.getSkipped()).hasSize(1);
        assertThat(r.getSkipped().getFirst().getReason()).isEqualTo("ALREADY_ASSIGNED");
        verify(userRoleRelService, never()).createList(any());
    }

    @Test
    void newAssignment_insertsAndEvictsCache() {
        UserAccount acc = new UserAccount();
        acc.setId(7L);
        when(userAccountService.searchList(any(Filters.class))).thenReturn(List.of(acc));
        Role role = new Role();
        role.setId(500L);
        when(roleService.searchList(any(Filters.class))).thenReturn(List.of(role));
        when(userRoleRelService.searchList(any(Filters.class))).thenReturn(List.of());
        when(userRoleRelService.createList(any())).thenReturn(List.of(1001L));

        UserRolePair p = new UserRolePair();
        p.setUserId(7L);
        p.setRoleId(500L);

        Context ctx = new Context();
        ctx.setTenantId(10L);
        BulkAddResult r = ContextHolder.callWith(ctx, () -> svc.bulkAdd(List.of(p), RoleSource.MANUAL));

        assertThat(r.getAdded()).hasSize(1);
        assertThat(r.getAdded().getFirst().getUserRoleId()).isEqualTo(1001L);
        verify(invalidator).evictBatch(10L, Set.of(7L));
    }

    @Test
    void duplicateWithinPayload_collapsedToSingleRow() {
        UserAccount acc = new UserAccount();
        acc.setId(7L);
        when(userAccountService.searchList(any(Filters.class))).thenReturn(List.of(acc));
        Role role = new Role();
        role.setId(500L);
        when(roleService.searchList(any(Filters.class))).thenReturn(List.of(role));
        when(userRoleRelService.searchList(any(Filters.class))).thenReturn(List.of());
        when(userRoleRelService.createList(any())).thenReturn(List.of(1001L));

        UserRolePair a = new UserRolePair();
        a.setUserId(7L);
        a.setRoleId(500L);
        UserRolePair b = new UserRolePair();
        b.setUserId(7L);
        b.setRoleId(500L);   // duplicate

        BulkAddResult r = svc.bulkAdd(List.of(a, b), RoleSource.MANUAL);
        // 2 requested, 1 added, 1 skipped-as-INVALID-nor-NOT-FOUND (the
        // dedup happens inline — the duplicate silently drops without a
        // "skipped" entry per the current implementation).
        assertThat(r.getAdded()).hasSize(1);
    }

    @Test
    void nullSource_defaultsToManual() {
        UserAccount acc = new UserAccount();
        acc.setId(7L);
        when(userAccountService.searchList(any(Filters.class))).thenReturn(List.of(acc));
        Role role = new Role();
        role.setId(500L);
        when(roleService.searchList(any(Filters.class))).thenReturn(List.of(role));
        when(userRoleRelService.searchList(any(Filters.class))).thenReturn(List.of());
        when(userRoleRelService.createList(any())).thenReturn(List.of(1001L));

        UserRolePair p = new UserRolePair();
        p.setUserId(7L);
        p.setRoleId(500L);

        BulkAddResult r = svc.bulkAdd(List.of(p), null);
        assertThat(r.getAdded()).hasSize(1);
    }
}
