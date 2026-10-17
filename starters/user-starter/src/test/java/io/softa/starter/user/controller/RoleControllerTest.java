package io.softa.starter.user.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.WizardSaveDTO;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.RoleNavigation;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.UserRoleSource;
import io.softa.starter.user.service.DynamicRoleSyncJob;
import io.softa.starter.user.service.RoleDataScopeService;
import io.softa.starter.user.service.RoleNavigationService;
import io.softa.starter.user.service.RoleSensitiveFieldSetService;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.SystemRoleWriteGuard;
import io.softa.starter.user.service.UserRoleRelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link RoleController} wizard endpoints — the atomic
 * create / rewrite paths that admins hit from the role editor UI.
 */
class RoleControllerTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private RoleService roleService;
    private RoleNavigationService roleNavigationService;
    private RoleDataScopeService roleDataScopeService;
    private RoleSensitiveFieldSetService roleSensitiveFieldSetService;
    private UserRoleRelService userRoleRelService;
    private DynamicRoleSyncJob dynamicRoleSyncJob;
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ModelService modelService;
    private io.softa.starter.user.service.impl.UiContextBuilder uiContextBuilder;
    private SystemRoleWriteGuard writeGuard;
    private RoleController controller;
    private io.softa.starter.user.service.RoleGrantCeiling roleGrantCeiling;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @BeforeEach
    void setUp() {
        roleService = mock(RoleService.class);
        roleNavigationService = mock(RoleNavigationService.class);
        roleDataScopeService = mock(RoleDataScopeService.class);
        roleSensitiveFieldSetService = mock(RoleSensitiveFieldSetService.class);
        userRoleRelService = mock(UserRoleRelService.class);
        dynamicRoleSyncJob = mock(DynamicRoleSyncJob.class);
        modelService = mock(ModelService.class);
        writeGuard = mock(SystemRoleWriteGuard.class);
        uiContextBuilder = mock(io.softa.starter.user.service.impl.UiContextBuilder.class);
        roleGrantCeiling = mock(io.softa.starter.user.service.RoleGrantCeiling.class);
        controller = new RoleController(
                roleService, roleNavigationService,
                roleDataScopeService, roleSensitiveFieldSetService,
                userRoleRelService, dynamicRoleSyncJob, uiContextBuilder, roleGrantCeiling);
        // Injected by Spring in production; set directly here (same package) because the write
        // verbs and their SystemRoleWriteGuard call now live in SystemRoleGuardedController.
        controller.modelService = castModelService(modelService);
        controller.writeGuard = writeGuard;
    }

    // ─── grant ceiling ───

    @Test
    void wizardSaves_areHeldToTheCallersOwnGrants_beforeAnyMemberIsAdded() {
        when(roleService.createOne(any(Role.class))).thenReturn(999L);
        org.mockito.Mockito.doThrow(new io.softa.framework.base.exception.BusinessException("out of reach"))
                .when(roleGrantCeiling).assertWithinCaller(999L);
        ObjectNode roleUpdate = JsonNodeFactory.instance.objectNode();
        roleUpdate.put("name", "Payroll Peek");
        ArrayNode userIds = JsonNodeFactory.instance.arrayNode();
        userIds.add(7L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.createWithWizard(
                        new WizardSaveDTO(roleUpdate, null, null, null, userIds)))
                .isInstanceOf(io.softa.framework.base.exception.BusinessException.class);
        org.mockito.Mockito.verify(userRoleRelService, org.mockito.Mockito.never()).createList(any());
    }

    @Test
    void wizardUpdate_isHeldToTheCallersOwnGrants() {
        ObjectNode roleUpdate = JsonNodeFactory.instance.objectNode();
        roleUpdate.put("name", "Payroll Peek");

        controller.saveWizard(5L, new WizardSaveDTO(roleUpdate, null, null, null, null));

        org.mockito.Mockito.verify(roleGrantCeiling).assertWithinCaller(5L);
    }

    // ─── createWithWizard ───

    @Test
    void createWithWizard_returnsNewRoleId_andWritesNavsAndManualMembers() {
        when(roleService.createOne(any(Role.class))).thenReturn(999L);

        ObjectNode roleUpdate = JSON.objectNode().put("name", "HR Manager");
        ArrayNode navs = JSON.arrayNode();
        navs.add(JSON.objectNode().put("navigationId", "hr.employee"));
        ArrayNode userIds = JSON.arrayNode().add(1L).add(2L);
        WizardSaveDTO body = new WizardSaveDTO(roleUpdate, navs, null, null, userIds);

        ApiResponse<Long> resp = controller.createWithWizard(body);

        assertThat(resp).isNotNull();
        // roleService.createOne fired with the Role built from wizard body.
        ArgumentCaptor<Role> roleCap = ArgumentCaptor.forClass(Role.class);
        verify(roleService).createOne(roleCap.capture());
        assertThat(roleCap.getValue().getId()).isNull();   // id cleared before create
        assertThat(roleCap.getValue().getName()).isEqualTo("HR Manager");
        // role_navigation rows written.
        verify(roleNavigationService).createList(any());
        // manual user_role rows written with source=MANUAL.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserRoleRel>> ursCap = ArgumentCaptor.forClass(List.class);
        verify(userRoleRelService).createList(ursCap.capture());
        assertThat(ursCap.getValue()).hasSize(2);
        assertThat(ursCap.getValue()).allSatisfy(r -> {
            assertThat(r.getSource()).isEqualTo(UserRoleSource.MANUAL);
            assertThat(r.getRoleId()).isEqualTo(999L);
        });
        // Dynamic sync also fires for the new role.
        verify(dynamicRoleSyncJob).syncRole(999L);
    }

    @Test
    void createWithWizard_emptyUserIds_noManualRows() {
        when(roleService.createOne(any(Role.class))).thenReturn(1L);

        WizardSaveDTO body = new WizardSaveDTO(
                JSON.objectNode().put("name", "R"), JSON.arrayNode(), null, null, JSON.arrayNode());
        controller.createWithWizard(body);

        verify(userRoleRelService, never()).createList(any());
    }

    // ─── saveWizard update path ───

    @Test
    void saveWizard_rewritesEverythingInSingleTransactionalCall() {
        WizardSaveDTO body = wizardBody(JSON.objectNode().put("name", "Renamed"),
                arrOf(navRow("hr.employee")),
                arrOf(1L, 2L));

        controller.saveWizard(500L, body);

        // Update on the role itself (ignoreNull=true — signature).
        verify(roleService).updateOne(any(Role.class), eq(true));
        // Old role_navigation rows wiped.
        verify(roleNavigationService).deleteByFilters(any(Filters.class));
        // New role_navigation rows written.
        verify(roleNavigationService).createList(any());
        // Old user_role rows (ALL sources) wiped.
        verify(userRoleRelService).deleteByFilters(any(Filters.class));
        // New MANUAL user_role rows written.
        verify(userRoleRelService).createList(any());
        // Then Dynamic sync fires.
        verify(dynamicRoleSyncJob).syncRole(500L);
    }

    @Test
    void saveWizard_orderIsUpdateThenWipeThenWriteThenDynamicSync() {
        WizardSaveDTO body = wizardBody(JSON.objectNode().put("name", "R"),
                arrOf(navRow("hr.employee")),
                arrOf(1L));

        controller.saveWizard(500L, body);

        // Sequencing matters: the wipe must precede the rewrite so the
        // final read state contains only the new rows.
        InOrder inOrder = inOrder(
                roleService, roleNavigationService, userRoleRelService, dynamicRoleSyncJob);
        inOrder.verify(roleService).updateOne(any(Role.class), anyBoolean());
        inOrder.verify(roleNavigationService).deleteByFilters(any(Filters.class));
        inOrder.verify(roleNavigationService).createList(any());
        inOrder.verify(userRoleRelService).deleteByFilters(any(Filters.class));
        inOrder.verify(userRoleRelService).createList(any());
        inOrder.verify(dynamicRoleSyncJob).syncRole(500L);
    }

    @Test
    void saveWizard_bindsRoleIdToPathVariable_notPayloadId() {
        // Payload carries id=123 (attempted hijack). Controller must overwrite
        // with the path-variable id (500) before updateOne fires.
        ObjectNode payload = JSON.objectNode().put("name", "R").put("id", 123);
        WizardSaveDTO body = wizardBody(payload, JSON.arrayNode(), JSON.arrayNode());

        controller.saveWizard(500L, body);

        ArgumentCaptor<Role> cap = ArgumentCaptor.forClass(Role.class);
        verify(roleService).updateOne(cap.capture(), anyBoolean());
        assertThat(cap.getValue().getId()).isEqualTo(500L);
    }

    @Test
    void saveWizard_dynamicFilterExplicitNull_triggersTargetedNullUpdate() {
        // Wizard "Clear" sends {"dynamicFilter": null}. The entity-based
        // updateOne(ignoreNull=true) skips null values, so a follow-up
        // map-based updateOne is required to actually write NULL.
        ObjectNode payload = JSON.objectNode();
        payload.put("name", "R");
        payload.set("dynamicFilter", JSON.nullNode());
        WizardSaveDTO body = wizardBody(payload, JSON.arrayNode(), JSON.arrayNode());

        controller.saveWizard(500L, body);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(modelService).updateOne(eq("Role"), cap.capture());
        assertThat(cap.getValue())
                .containsEntry("id", 500L)
                .containsEntry("dynamicFilter", null);
    }

    @Test
    void saveWizard_dynamicFilterAbsent_noTargetedNullUpdate() {
        // Payload never mentions dynamicFilter → the wizard didn't touch it →
        // don't issue the follow-up nulling update.
        ObjectNode payload = JSON.objectNode().put("name", "R");
        WizardSaveDTO body = wizardBody(payload, JSON.arrayNode(), JSON.arrayNode());

        controller.saveWizard(500L, body);

        verify(modelService, never()).updateOne(eq("Role"), any());
    }

    @Test
    void saveWizard_emptyUserIds_stillWipesButNoCreate() {
        WizardSaveDTO body = wizardBody(JSON.objectNode(), JSON.arrayNode(), JSON.arrayNode());

        controller.saveWizard(500L, body);

        // Wipe still happens (fresh start), no MANUAL rows to insert.
        verify(userRoleRelService).deleteByFilters(any(Filters.class));
        verify(userRoleRelService, never()).createList(any());
    }

    @Test
    void saveWizard_emptyRoleNavigations_wipesButNoCreate() {
        WizardSaveDTO body = wizardBody(JSON.objectNode(), JSON.arrayNode(), arrOf(1L));

        controller.saveWizard(500L, body);

        verify(roleNavigationService).deleteByFilters(any(Filters.class));
        verify(roleNavigationService, never()).createList(any());
    }

    @Test
    void saveWizard_roleNavigationsPopulated_writesCorrectRoleId() {
        WizardSaveDTO body = wizardBody(
                JSON.objectNode(),
                arrOf(navRow("hr.employee"), navRow("hr.department")),
                JSON.arrayNode());

        controller.saveWizard(500L, body);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoleNavigation>> cap = ArgumentCaptor.forClass(List.class);
        verify(roleNavigationService).createList(cap.capture());
        assertThat(cap.getValue()).hasSize(2)
                .allSatisfy(rn -> assertThat(rn.getRoleId()).isEqualTo(500L));
    }

    @Test
    void saveWizard_duplicateUserIdsInPayload_dedupedIntoSingleRow() {
        // Wizard client may send the same id twice; controller de-dupes into
        // one MANUAL row per (user, role) — matches the UK on user_role_rel.
        WizardSaveDTO body = wizardBody(JSON.objectNode(), JSON.arrayNode(),
                arrOf(7L, 7L, 8L));

        controller.saveWizard(500L, body);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserRoleRel>> cap = ArgumentCaptor.forClass(List.class);
        verify(userRoleRelService).createList(cap.capture());
        assertThat(cap.getValue()).hasSize(2)
                .extracting(UserRoleRel::getUserId)
                .containsExactlyInAnyOrder(7L, 8L);
    }

    // ─── helpers ───

    private static WizardSaveDTO wizardBody(JsonNode roleUpdate, JsonNode navs, JsonNode userIds) {
        return new WizardSaveDTO(roleUpdate, navs, null, null, userIds);
    }

    private static ArrayNode arrOf(Object... items) {
        ArrayNode arr = JSON.arrayNode();
        for (Object o : items) {
            if (o instanceof Number n) arr.add(n.longValue());
            else if (o instanceof JsonNode j) arr.add(j);
            else if (o != null) arr.add(o.toString());
        }
        return arr;
    }

    private static ObjectNode navRow(String navId) {
        return JSON.objectNode().put("navigationId", navId);
    }

    @SuppressWarnings("unchecked")
    private static ModelService<Long> castModelService(ModelService<?> service) {
        return (ModelService<Long>) service;
    }
}
