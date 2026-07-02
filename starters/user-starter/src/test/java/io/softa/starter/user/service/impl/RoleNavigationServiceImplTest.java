package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.entity.RoleNavigation;
import io.softa.starter.user.event.RoleNavigationChangedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RoleNavigationServiceImplTest {

    private ApplicationEventPublisher events;

    @BeforeEach
    void setUp() {
        events = mock(ApplicationEventPublisher.class);
    }

    @Test
    void publish_nullTenant_stillFiresEvent() throws Exception {
        // Reach into private publish() via reflection — the behavior we want
        // to lock in (fire the event even when tenantId is null so downstream
        // eviction can decide) is otherwise reached only via super-method
        // chained calls we can't stub.
        java.lang.reflect.Method m = RoleNavigationServiceImpl.class
                .getDeclaredMethod("publish", Set.class);
        m.setAccessible(true);

        RoleNavigationServiceImpl fresh = new RoleNavigationServiceImpl(events);
        m.invoke(fresh, Set.of(500L));

        ArgumentCaptor<RoleNavigationChangedEvent> cap =
                ArgumentCaptor.forClass(RoleNavigationChangedEvent.class);
        verify(events).publishEvent(cap.capture());
        assertThat(cap.getValue().tenantId()).isNull();
        assertThat(cap.getValue().roleId()).isEqualTo(500L);
    }

    @Test
    void publish_emptyRoleIds_noEvent() throws Exception {
        java.lang.reflect.Method m = RoleNavigationServiceImpl.class
                .getDeclaredMethod("publish", Set.class);
        m.setAccessible(true);

        RoleNavigationServiceImpl fresh = new RoleNavigationServiceImpl(events);
        m.invoke(fresh, Set.of());
        m.invoke(fresh, (Object) null);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void publish_multipleRoleIds_firesOnePerRole() throws Exception {
        java.lang.reflect.Method m = RoleNavigationServiceImpl.class
                .getDeclaredMethod("publish", Set.class);
        m.setAccessible(true);

        RoleNavigationServiceImpl fresh = new RoleNavigationServiceImpl(events);
        Context ctx = new Context();
        ctx.setTenantId(10L);
        ContextHolder.runWith(ctx, () -> {
            try {
                m.invoke(fresh, Set.of(1L, 2L, 3L));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        verify(events, org.mockito.Mockito.times(3)).publishEvent(any(RoleNavigationChangedEvent.class));
    }

    @Test
    void roleIdsFromFilters_extractsSingleEqualLeaf() throws Exception {
        java.lang.reflect.Method m = RoleNavigationServiceImpl.class
                .getDeclaredMethod("roleIdsFromFilters", Filters.class);
        m.setAccessible(true);

        Filters filters = new Filters().eq(RoleNavigation::getRoleId, 500L);
        @SuppressWarnings("unchecked")
        Set<Long> result = (Set<Long>) m.invoke(null, filters);
        assertThat(result).containsExactly(500L);
    }

    @Test
    void roleIdsFromFilters_nonEqualLeaf_returnsEmpty() throws Exception {
        java.lang.reflect.Method m = RoleNavigationServiceImpl.class
                .getDeclaredMethod("roleIdsFromFilters", Filters.class);
        m.setAccessible(true);

        // IN(...) is NOT an EQUAL leaf → the AST extractor stays conservative.
        Filters filters = new Filters().in(RoleNavigation::getRoleId, List.of(1L, 2L));
        @SuppressWarnings("unchecked")
        Set<Long> result = (Set<Long>) m.invoke(null, filters);
        assertThat(result).isEmpty();
    }

    @Test
    void roleIdsFromFilters_nonRoleIdField_returnsEmpty() throws Exception {
        java.lang.reflect.Method m = RoleNavigationServiceImpl.class
                .getDeclaredMethod("roleIdsFromFilters", Filters.class);
        m.setAccessible(true);

        Filters filters = new Filters().eq(RoleNavigation::getNavigationId, "nav-a");
        @SuppressWarnings("unchecked")
        Set<Long> result = (Set<Long>) m.invoke(null, filters);
        assertThat(result).isEmpty();
    }

    @Test
    void roleIdsFromFilters_nullFilter_returnsEmpty() throws Exception {
        java.lang.reflect.Method m = RoleNavigationServiceImpl.class
                .getDeclaredMethod("roleIdsFromFilters", Filters.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Long> result = (Set<Long>) m.invoke(null, (Filters) null);
        assertThat(result).isEmpty();
    }
}
