package io.softa.starter.user.controller;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.dto.WizardSaveDTO;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.service.DepartmentGrants;
import io.softa.starter.user.service.RoleGrantCeiling;
import io.softa.starter.user.service.RoleSaveValidator;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.SystemRoleWriteGuard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The department grant through the role wizard — written, left alone, cleared — and the application's
 * role-save validators.
 */
class RoleDepartmentGrantWizardTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SUBTREE_5 = "[{\"scopeType\":\"DEPT_SUBTREE\",\"scopeExpr\":{\"deptId\":\"5\"}}]";

    private MockedStatic<ModelManager> modelManager;
    private RoleService roleService;
    private RoleGrantCeiling ceiling;
    private ModelService<Long> modelService;
    private RoleController controller;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
        declareColumn(true);
        roleService = mock(RoleService.class);
        when(roleService.createOne(any(Role.class))).thenReturn(42L);
        ceiling = mock(RoleGrantCeiling.class);
        modelService = mock(ModelService.class);
        controller = controllerWith(roleService, ceiling);
        controller.modelService = modelService;
        controller.writeGuard = mock(SystemRoleWriteGuard.class);
    }

    /**
     * The controller over its one constructor, every collaborator a mock but the two the tests drive. Built
     * from the constructor's own parameters, so a collaborator added to it later needs no change here.
     */
    private static RoleController controllerWith(RoleService roleService, RoleGrantCeiling ceiling) {
        Constructor<?> constructor = RoleController.class.getConstructors()[0];
        Object[] args = Arrays.stream(constructor.getParameterTypes())
                .map(type -> type == RoleService.class ? roleService
                        : type == RoleGrantCeiling.class ? ceiling
                        : mock(type))
                .toArray();
        try {
            return (RoleController) constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    private void declareColumn(boolean declared) {
        modelManager.when(() -> ModelManager.getModelFieldOrNull("Role", DepartmentGrants.FIELD))
                .thenReturn(declared ? mock(MetaField.class) : null);
    }

    private static ObjectNode roleUpdate(String departmentGrant) {
        ObjectNode node = JSON.createObjectNode().put("name", "Regional Support");
        if (departmentGrant != null) {
            node.set(DepartmentGrants.FIELD, JSON.readTree(departmentGrant));
        }
        return node;
    }

    private static WizardSaveDTO body(ObjectNode roleUpdate) {
        return new WizardSaveDTO(roleUpdate, null, null, null, null);
    }

    private Role created() {
        ArgumentCaptor<Role> role = ArgumentCaptor.forClass(Role.class);
        verify(roleService).createOne(role.capture());
        return role.getValue();
    }

    @Test
    void aGrantInThePayloadIsWritten() {
        controller.createWithWizard(body(roleUpdate(SUBTREE_5)));

        assertThat(created().getDepartmentGrant()).isEqualTo(JSON.readTree(SUBTREE_5));
    }

    @Test
    void anEmptyGrantIsWrittenAsEmptyNotDropped() {
        controller.createWithWizard(body(roleUpdate("[]")));

        JsonNode written = created().getDepartmentGrant();
        assertThat(written.isArray()).isTrue();
        assertThat(written.isEmpty()).isTrue();
    }

    @Test
    void aPayloadWithoutTheGrantLeavesItAlone() {
        controller.saveWizard(5L, body(roleUpdate(null)));

        ArgumentCaptor<Role> role = ArgumentCaptor.forClass(Role.class);
        verify(roleService).updateOne(role.capture(), eq(true));
        assertThat(role.getValue().getDepartmentGrant()).isNull();
        verify(modelService, never()).updateOne(eq("Role"), anyMap());
    }

    @Test
    void anExplicitNullClearsTheGrantBackToUnconfigured() {
        ObjectNode update = roleUpdate(null);
        update.putNull(DepartmentGrants.FIELD);

        controller.saveWizard(5L, body(update));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> clear = ArgumentCaptor.forClass(Map.class);
        verify(modelService).updateOne(eq("Role"), clear.capture());
        assertThat(clear.getValue()).containsEntry("id", 5L).containsEntry(DepartmentGrants.FIELD, null)
                .doesNotContainKey("dynamicFilter");
    }

    @Test
    void aGrantTheRuntimeWouldReadDifferentlyIsRefused() {
        assertThatThrownBy(() -> controller.createWithWizard(body(roleUpdate("[{\"scopeType\":\"DEPT_SUBTREE\"}]"))))
                .isInstanceOf(BusinessException.class);
        verify(roleService, never()).createOne(any(Role.class));
    }

    @Test
    void aGrantIsRefusedWhereTheRoleModelHasNoColumnForIt() {
        declareColumn(false);

        assertThatThrownBy(() -> controller.createWithWizard(body(roleUpdate(SUBTREE_5))))
                .isInstanceOf(BusinessException.class);
    }

    // ---- role-save validators ----------------------------------------------

    @Test
    void validatorsRunAfterTheCeilingOnWhatWasWritten() {
        List<String> calls = new ArrayList<>();
        controller.roleSaveValidators = List.of((roleId, created) -> {
            calls.add(roleId + ":" + created);
            return Optional.empty();
        });

        controller.createWithWizard(body(roleUpdate(null)));
        controller.saveWizard(5L, body(roleUpdate(null)));

        assertThat(calls).containsExactly("42:true", "5:false");
    }

    @Test
    void aRefusalStopsTheSaveWithTheApplicationsSentence() {
        RoleSaveValidator first = mock(RoleSaveValidator.class);
        RoleSaveValidator second = mock(RoleSaveValidator.class);
        when(first.validate(42L, true)).thenReturn(Optional.of("Pick the companies this role reaches {first}."));
        controller.roleSaveValidators = List.of(first, second);

        assertThatThrownBy(() -> controller.createWithWizard(body(roleUpdate(null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Pick the companies this role reaches");
        var order = inOrder(ceiling, first);
        order.verify(ceiling).assertWithinCaller(42L);
        order.verify(first).validate(42L, true);
        verify(second, never()).validate(any(), anyBoolean());
    }
}
