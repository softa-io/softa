package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.event.RoleNavigationChangedEvent;
import io.softa.starter.user.event.UserRoleRelChangedEvent;
import io.softa.starter.user.service.PermissionInfoEnricher;
import io.softa.starter.user.service.UserRoleRelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionCacheInvalidatorImplTest {

    private UserRoleRelService userRoleRelService;
    private CacheService cacheService;
    private PermissionCacheInvalidatorImpl invalidator;

    @BeforeEach
    void setUp() {
        userRoleRelService = mock(UserRoleRelService.class);
        cacheService = mock(CacheService.class);
        invalidator = new PermissionCacheInvalidatorImpl(userRoleRelService, cacheService);
    }

    @Test
    void evictOne_clearsCanonicalKey() {
        invalidator.evictOne(10L, 42L);
        String expected = PermissionInfoEnricher.cacheKey(10L, 42L);
        verify(cacheService).clear(expected);
    }

    @Test
    void evictOne_nullUserId_noop() {
        invalidator.evictOne(10L, null);
        verify(cacheService, never()).clear(anyString());
    }

    @Test
    void evictOne_nullTenantId_skipsAndDoesNotCallCache() {
        invalidator.evictOne(null, 42L);
        verify(cacheService, never()).clear(anyString());
    }

    @Test
    void evictOne_swallowsCacheFailure() {
        // Redis offline / serialization error — must not propagate.
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(cacheService).clear(anyString());
        invalidator.evictOne(10L, 42L);   // no throw expected
    }

    @Test
    void evictBatch_clearsAllKeysInOneCall() {
        invalidator.evictBatch(10L, Set.of(1L, 2L, 3L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(cacheService).clear(keys.capture());
        assertThat(keys.getValue()).hasSize(3);
        assertThat(keys.getValue())
                .containsExactlyInAnyOrder(
                        PermissionInfoEnricher.cacheKey(10L, 1L),
                        PermissionInfoEnricher.cacheKey(10L, 2L),
                        PermissionInfoEnricher.cacheKey(10L, 3L));
    }

    @Test
    void evictBatch_nullOrEmptyUserIds_noop() {
        invalidator.evictBatch(10L, null);
        invalidator.evictBatch(10L, Set.of());
        verify(cacheService, never()).clear(anyList());
    }

    @Test
    void evictBatch_nullTenant_skipsAll() {
        invalidator.evictBatch(null, Set.of(1L, 2L));
        verify(cacheService, never()).clear(anyList());
    }

    @Test
    void evictBatch_filtersOutNullUserIds() {
        Set<Long> userIds = new java.util.HashSet<>();
        userIds.add(1L);
        userIds.add(null);
        userIds.add(2L);
        invalidator.evictBatch(10L, userIds);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(cacheService).clear(keys.capture());
        assertThat(keys.getValue()).hasSize(2);
    }

    @Test
    void evictByRole_noHolders_doesNothing() {
        when(userRoleRelService.searchList(any(io.softa.framework.orm.domain.Filters.class))).thenReturn(List.of());
        invalidator.evictByRole(10L, 500L);
        verify(cacheService, never()).clear(anyList());
    }

    @Test
    void evictByRole_holdersPresent_batchEvicts() {
        UserRoleRel r1 = new UserRoleRel();
        r1.setUserId(1L);
        UserRoleRel r2 = new UserRoleRel();
        r2.setUserId(2L);
        when(userRoleRelService.searchList(any(io.softa.framework.orm.domain.Filters.class))).thenReturn(List.of(r1, r2));

        // Run inside a Context so the @SkipPermissionCheck-driven mutation
        // in usersHoldingRole doesn't NPE on ContextHolder.getContext().
        Context ctx = new Context();
        ctx.setTenantId(10L);
        ctx.setUserId(1L);
        ContextHolder.runWith(ctx, () -> invalidator.evictByRole(10L, 500L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(cacheService).clear(keys.capture());
        assertThat(keys.getValue()).hasSize(2);
    }

    @Test
    void evictByRole_nullTenantOrRole_noop() {
        invalidator.evictByRole(null, 500L);
        invalidator.evictByRole(10L, null);
        verify(cacheService, never()).clear(anyList());
        verify(userRoleRelService, never()).searchList(any(io.softa.framework.orm.domain.Filters.class));
    }

    @Test
    void onUserRoleRelChanged_emptyUserIds_logsAndSkips() {
        UserRoleRelChangedEvent ev = new UserRoleRelChangedEvent(10L, Set.of());
        invalidator.onUserRoleRelChanged(ev);
        verify(cacheService, never()).clear(anyList());
    }

    @Test
    void onUserRoleRelChanged_userIdsSet_evictsBatch() {
        UserRoleRelChangedEvent ev = UserRoleRelChangedEvent.forUsers(10L, Set.of(7L, 8L));
        invalidator.onUserRoleRelChanged(ev);
        verify(cacheService, times(1)).clear(anyList());
    }

    @Test
    void onRoleNavigationChanged_nullRoleId_noop() {
        RoleNavigationChangedEvent ev = new RoleNavigationChangedEvent(10L, null);
        invalidator.onRoleNavigationChanged(ev);
        verify(cacheService, never()).clear(anyList());
    }
}
