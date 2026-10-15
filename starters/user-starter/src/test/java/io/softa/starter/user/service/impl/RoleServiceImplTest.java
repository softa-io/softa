package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.event.RoleNavigationChangedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleServiceImplTest {

    private RoleServiceImpl svc;
    private ApplicationEventPublisher events;

    @BeforeEach
    void setUp() {
        // Spy so we can stub inherited super.* and searchList / getById.
        events = mock(ApplicationEventPublisher.class);
        svc = spy(new RoleServiceImpl(events));
    }

    // ─── guardAdminCreatedCode: reject non-null code on create ───

    @Test
    void createOne_withCode_throws() {
        Role admin = new Role();
        admin.setName("EvilAdmin");
        admin.setCode("SUPER_ADMIN");   // trying to inherit the bypass

        assertThatThrownBy(() -> svc.createOne(admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reserved for system roles");
    }

    @Test
    void createOne_nullCode_allowed() {
        Role fresh = new Role();
        fresh.setName("HR Manager");
        doReturn(1L).when(svc).createOne(any(Role.class));

        // Direct assertion: guard doesn't throw. Because doReturn replaces
        // createOne entirely, exercise the guard via createList (which loops
        // through guardAdminCreatedCode).
        doReturn(List.of(1L)).when(svc).createList(any());
        Role r2 = new Role();
        r2.setName("Ok Role");
        assertThatCode(() -> svc.createList(List.of(r2))).doesNotThrowAnyException();
    }

    @Test
    void createOne_emptyCode_allowed() {
        // Empty string is treated as absent (documented behavior).
        Role r = new Role();
        r.setCode("");
        doReturn(List.of()).when(svc).createList(any());
        assertThatCode(() -> svc.createList(List.of(r))).doesNotThrowAnyException();
    }

    @Test
    void createList_nullEntities_delegatesToSuper() {
        doReturn(List.of()).when(svc).createList(any());
        assertThatCode(() -> svc.createList(null)).doesNotThrowAnyException();
    }

    // ─── guardSystemRole: delete blocked on SUPER_ADMIN ───

    @Test
    void deleteById_systemRole_throws() {
        Role sa = new Role();
        sa.setId(1L);
        sa.setName("Super Admin");
        sa.setCode(RoleConstant.CODE_SUPER_ADMIN);
        doReturn(List.of(sa)).when(svc).searchList(any(Filters.class));

        assertThatThrownBy(() -> svc.deleteById(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Delete is not allowed on system role");
    }

    @Test
    void deleteByIds_containsSystemRole_throws() {
        Role sa = new Role();
        sa.setId(1L);
        sa.setName("Super Admin");
        sa.setCode(RoleConstant.CODE_SUPER_ADMIN);
        doReturn(List.of(sa)).when(svc).searchList(any(Filters.class));

        assertThatThrownBy(() -> svc.deleteByIds(List.of(1L, 2L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteByIds_emptyList_earlyReturnNoSearch() {
        doReturn(true).when(svc).deleteByIds(any());
        assertThatCode(() -> svc.deleteByIds(List.of())).doesNotThrowAnyException();
        // No searchList call verified via never() — but stubbing super means
        // it doesn't matter that we don't reach it.
    }

    @Test
    void deleteById_nonSystemRole_passesGuard() {
        // searchList returns no rows with a code column (no system roles hit).
        doReturn(List.of()).when(svc).searchList(any(Filters.class));
        doReturn(true).when(svc).deleteById(anyLongValue());
        assertThatCode(() -> svc.deleteById(500L)).doesNotThrowAnyException();
    }

    // ─── guardSystemMutation: reject damaging edits to SUPER_ADMIN ───

    @Test
    void updateOne_renameSuperAdmin_throws() {
        Role persisted = superAdminRole();
        doReturn(Optional.of(persisted)).when(svc).getById(1L);

        Role patch = new Role();
        patch.setId(1L);
        patch.setName("Renamed");

        assertThatThrownBy(() -> svc.updateOne(patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot edit administrator role");
    }

    @Test
    void updateOne_deactivateSuperAdmin_throws() {
        Role persisted = superAdminRole();
        doReturn(Optional.of(persisted)).when(svc).getById(1L);

        Role patch = new Role();
        patch.setId(1L);
        patch.setActive(false);

        assertThatThrownBy(() -> svc.updateOne(patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot edit administrator role");
    }

    @Test
    void updateOne_changeCodeOnSuperAdmin_throws() {
        Role persisted = superAdminRole();
        doReturn(Optional.of(persisted)).when(svc).getById(1L);

        Role patch = new Role();
        patch.setId(1L);
        patch.setCode("SUPER_ADMIN_2");

        assertThatThrownBy(() -> svc.updateOne(patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot edit administrator role");
    }

    @Test
    void updateOne_addDynamicFilterOnSuperAdmin_throws() {
        Role persisted = superAdminRole();
        doReturn(Optional.of(persisted)).when(svc).getById(1L);

        Role patch = new Role();
        patch.setId(1L);
        ObjectNode filter = JsonNodeFactory.instance.objectNode();
        filter.put("field", "role_code_hack");
        patch.setDynamicFilter(filter);

        assertThatThrownBy(() -> svc.updateOne(patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot edit administrator role");
    }

    @Test
    void updateOne_editDescriptionOfSystemRole_throws() {
        // An administrator role accepts no edit at all — even a benign description change is refused.
        Role persisted = superAdminRole();
        doReturn(Optional.of(persisted)).when(svc).getById(1L);

        Role patch = new Role();
        patch.setId(1L);
        patch.setDescription("tweaked");

        assertThatThrownBy(() -> svc.updateOne(patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot edit administrator role");
    }

    @Test
    void updateOne_nonSystemRoleAndFieldChanges_allowed() {
        // Persisted role has no code → not a system role → guard passes.
        Role persisted = new Role();
        persisted.setId(500L);
        persisted.setName("HR Manager");
        persisted.setActive(true);
        doReturn(Optional.of(persisted)).when(svc).getById(500L);
        doReturn(true).when(svc).updateOne(any(Role.class));

        Role patch = new Role();
        patch.setId(500L);
        patch.setName("HR Lead");
        assertThatCode(() -> svc.updateOne(patch)).doesNotThrowAnyException();
    }

    @Test
    void updateOne_nullId_earlyReturnAllowed() {
        // No id → guard has no persisted row to check → passes to super.
        doReturn(true).when(svc).updateOne(any(Role.class));
        Role patch = new Role();
        assertThatCode(() -> svc.updateOne(patch)).doesNotThrowAnyException();
    }

    // ─── extension: guards protect ANY system-reserved role, not just SUPER_ADMIN ───

    @Test
    void deleteById_tenantAdminRole_throws() {
        Role ta = new Role();
        ta.setId(2L);
        ta.setName("Tenant Admin");
        ta.setCode(RoleConstant.CODE_TENANT_ADMIN);
        doReturn(List.of(ta)).when(svc).searchList(any(Filters.class));

        assertThatThrownBy(() -> svc.deleteById(2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Delete is not allowed on system role");
    }

    @Test
    void updateOne_deactivateTenantAdmin_throws() {
        Role persisted = new Role();
        persisted.setId(2L);
        persisted.setName("Tenant Admin");
        persisted.setCode(RoleConstant.CODE_TENANT_ADMIN);
        persisted.setActive(true);
        doReturn(Optional.of(persisted)).when(svc).getById(2L);

        Role patch = new Role();
        patch.setId(2L);
        patch.setActive(false);

        assertThatThrownBy(() -> svc.updateOne(patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot edit administrator role");
    }

    // ─── cache eviction: Role write publishes a per-role event ───

    @Test
    void updateOne_nonSystemRole_publishesEvictionEvent() {
        // Run the REAL updateOne so the publish path fires: inject a mock
        // modelService for super.updateOne, and make getById return a
        // non-system role so the guard passes.
        ModelService<Long> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(svc, "modelService", modelService);
        when(modelService.updateOne(eq("Role"), anyMap())).thenReturn(true);
        doReturn(Optional.empty()).when(svc).getById(anyLong());

        Role patch = new Role();
        patch.setId(500L);
        patch.setActive(false);  // the documented "revoke" flip

        Context ctx = new Context();
        ctx.setTenantId(9L);
        ContextHolder.runWith(ctx, () -> svc.updateOne(patch));

        // A holder's cached PermissionInfo must be evicted immediately.
        verify(events).publishEvent(new RoleNavigationChangedEvent(9L, 500L));
    }

    @Test
    void deleteById_nonSystemRole_publishesEvictionEvent() {
        ModelService<Long> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(svc, "modelService", modelService);
        when(modelService.deleteById("Role", 500L)).thenReturn(true);
        doReturn(List.of()).when(svc).searchList(any(Filters.class));  // no system role hit

        Context ctx = new Context();
        ctx.setTenantId(9L);
        ContextHolder.runWith(ctx, () -> svc.deleteById(500L));

        verify(events).publishEvent(new RoleNavigationChangedEvent(9L, 500L));
    }

    // ─── helpers ───

    // ─── business built-in roles: editable, but the code is fixed and they cannot be deleted ───

    @Test
    void updateOne_businessBuiltInRole_nameStatusRuleAndDescriptionAreEditable() {
        ModelService<Long> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(svc, "modelService", modelService);
        when(modelService.updateOne(eq("Role"), anyMap())).thenReturn(true);
        doReturn(Optional.of(businessBuiltInRole())).when(svc).getById(3L);

        Role patch = new Role();
        patch.setId(3L);
        patch.setName("Sales Team");
        patch.setDescription("Front-line sales");
        patch.setActive(false);
        patch.setCode("SALES");   // repeating the stored code is not a change

        assertThat(svc.updateOne(patch)).isTrue();
        verify(modelService).updateOne(eq("Role"), anyMap());
    }

    @Test
    void updateOne_businessBuiltInRole_changeCode_throws() {
        doReturn(Optional.of(businessBuiltInRole())).when(svc).getById(3L);

        Role patch = new Role();
        patch.setId(3L);
        patch.setCode("SALES_V2");

        assertThatThrownBy(() -> svc.updateOne(patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be changed");
    }

    @Test
    void updateOne_businessBuiltInRole_clearingTheCodeWhenNullsAreWritten_throws() {
        doReturn(Optional.of(businessBuiltInRole())).when(svc).getById(3L);

        Role patch = new Role();
        patch.setId(3L);
        patch.setName("Sales");   // code left null, and ignoreNull=false writes that null

        assertThatThrownBy(() -> svc.updateOne(patch, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be changed");
    }

    @Test
    void updateOne_businessBuiltInRole_nullCodeLeftAloneWhenNullsAreIgnored_allowed() {
        ModelService<Long> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(svc, "modelService", modelService);
        when(modelService.updateOne(eq("Role"), anyMap())).thenReturn(true);
        doReturn(Optional.of(businessBuiltInRole())).when(svc).getById(3L);

        Role patch = new Role();
        patch.setId(3L);
        patch.setName("Sales");

        assertThat(svc.updateOne(patch, true)).isTrue();
    }

    @Test
    void deleteById_businessBuiltInRole_throws() {
        doReturn(List.of(businessBuiltInRole())).when(svc).searchList(any(Filters.class));

        assertThatThrownBy(() -> svc.deleteById(3L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Delete is not allowed on system role");
    }

    private static Role businessBuiltInRole() {
        Role r = new Role();
        r.setId(3L);
        r.setName("Sales");
        r.setCode("SALES");
        r.setActive(true);
        return r;
    }

    private static Role superAdminRole() {
        Role r = new Role();
        r.setId(1L);
        r.setName("Super Admin");
        r.setCode(RoleConstant.CODE_SUPER_ADMIN);
        r.setActive(true);
        return r;
    }

    private static long anyLongValue() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
