package io.softa.starter.user.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.EmpInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.RoleDataScope;
import io.softa.starter.user.entity.UserRoleRel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ceiling read from stored roles the way a save and an assignment read them: a caller whose reach is
 * worked out from their employee is judged by it, and what a role already holds is not judged as new.
 */
class RoleGrantCeilingOnStoredRolesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TICKET = "Ticket";
    private static final long CALLER_ROLE = 1L;
    private static final long TARGET_ROLE = 2L;
    private static final long CALLER_DEPARTMENT = 5L;

    private final RoleService roleService = mock(RoleService.class);
    private final RoleDataScopeService scopeService = mock(RoleDataScopeService.class);
    private final UserRoleRelService relService = mock(UserRoleRelService.class);
    @SuppressWarnings("rawtypes")
    private final ModelService modelService = mock(ModelService.class);
    private final RoleGrantCeiling ceiling = new RoleGrantCeiling(roleService, mock(RoleNavigationService.class),
            scopeService, mock(RoleSensitiveFieldSetService.class), relService, modelService);

    private MockedStatic<ModelManager> modelManager;
    private Context context;
    private Role callerRole;
    private Role targetRole;
    private RoleDataScope targetScope;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.getModelFieldOrNull("Role", DepartmentGrants.FIELD))
                .thenReturn(mock(MetaField.class));
        modelManager.when(() -> ModelManager.existModel(TICKET)).thenReturn(true);
        modelManager.when(() -> ModelManager.existModel(ModelConstant.DEPARTMENT_MODEL)).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(ModelConstant.DEPARTMENT_MODEL, "idPath"))
                .thenReturn(mock(MetaField.class));
        modelManager.when(() -> ModelManager.getModel(TICKET)).thenReturn(mock(MetaModel.class));
        MetaField department = mock(MetaField.class);
        when(department.getFieldType()).thenReturn(FieldType.MANY_TO_ONE);
        when(department.getRelatedModel()).thenReturn(ModelConstant.DEPARTMENT_MODEL);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(TICKET, ModelConstant.DEPARTMENT_FIELD))
                .thenReturn(department);

        callerRole = role(CALLER_ROLE, "[{\"scopeType\":\"OWN_DEPARTMENT\"}]");
        targetRole = role(TARGET_ROLE, "[{\"scopeType\":\"DEPT_SUBTREE\",\"scopeExpr\":{\"deptId\":\"5\"}}]");
        targetScope = scope(TARGET_ROLE);
        when(roleService.searchList(any(Filters.class))).thenAnswer(call ->
                call.getArgument(0).toString().contains("active") ? List.of(callerRole) : List.of(callerRole, targetRole));
        when(scopeService.searchList(any(Filters.class))).thenAnswer(call -> List.of(scope(CALLER_ROLE), targetScope));
        UserRoleRel held = new UserRoleRel();
        held.setRoleId(CALLER_ROLE);
        when(relService.searchList(any(Filters.class))).thenReturn(List.of(held));
        when(modelService.searchList(anyString(), any(FlexQuery.class))).thenAnswer(call ->
                ModelConstant.DEPARTMENT_MODEL.equals(call.getArgument(0))
                        ? List.of(Map.of("id", 5L, "idPath", "/1/5"), Map.of("id", 6L, "idPath", "/1/6"))
                        : List.of());

        context = new Context();
        context.setTenantId(1L);
        context.setUserId(100L);
        context.setRoleCodes(Set.of("HR"));
        EmpInfo employee = new EmpInfo();
        employee.setDeptId(CALLER_DEPARTMENT);
        context.setEmpInfo(employee);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void aCallersOwnDepartmentReachesThatDepartmentWhenJudgedInTheTenantContext() {
        assertThat(assignment()).isEmpty();
        assertThat(ContextHolder.callWith(context, ceiling::callerGrants)).singleElement()
                .satisfies(grants -> assertThat(grants.departmentPaths()).containsExactly("/1/5"));
    }

    @Test
    void aDepartmentBeyondTheCallersOwnIsStillRefused() {
        targetRole.setDepartmentGrant(json("[{\"scopeType\":\"DEPT_SUBTREE\",\"scopeExpr\":{\"deptId\":\"6\"}}]"));

        assertThat(assignment()).isPresent();
    }

    @Test
    void withoutAnEmployeeTheCallersConditionNamesNothing() {
        context.setEmpInfo(null);

        assertThat(assignment()).isPresent();
    }

    @Test
    void aTargetRolesConditionIsNotReadAsTheCallersDepartments() {
        targetRole.setDepartmentGrant(json("[{\"scopeType\":\"OWN_DEPARTMENT\"}]"));

        assertThat(stored().departmentPaths()).isEmpty();
    }

    @Test
    void keepingAConditionTheCallerCouldNotGrantPassesASaveButAddingOneDoesNot() {
        targetRole.setDepartmentGrant(json("[{\"scopeType\":\"MANAGED_DEPARTMENTS\"}]"));
        RoleGrantCeiling.Grants before = stored();

        assertThatCode(() -> save(before)).doesNotThrowAnyException();

        targetRole.setDepartmentGrant(json("[{\"scopeType\":\"MANAGED_DEPARTMENTS\"},{\"scopeType\":\"DIRECT_REPORTS\"}]"));
        assertThatThrownBy(() -> save(before))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("worked out for whoever holds it");
    }

    @Test
    void aSaveWithNoEarlierStateJudgesEveryConditionAsNew() {
        targetRole.setDepartmentGrant(json("[{\"scopeType\":\"MANAGED_DEPARTMENTS\"}]"));

        assertThatThrownBy(() -> ContextHolder.runWith(context, () -> ceiling.assertWithinCaller(TARGET_ROLE))).isInstanceOf(BusinessException.class);
    }

    @Test
    void keepingAnExemptionPassesASaveButAddingOneDoesNot() {
        targetScope.setDepartmentGrantExempt(true);
        RoleGrantCeiling.Grants withExemption = stored();
        assertThatCode(() -> save(withExemption)).doesNotThrowAnyException();

        targetScope.setDepartmentGrantExempt(false);
        RoleGrantCeiling.Grants without = stored();
        targetScope.setDepartmentGrantExempt(true);
        assertThatThrownBy(() -> save(without))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void assigningARoleDoesNotJudgeItsConditionsOrExemptions() {
        targetRole.setDepartmentGrant(json("[{\"scopeType\":\"MANAGED_DEPARTMENTS\"}]"));
        targetScope.setDepartmentGrantExempt(true);

        assertThat(assignment()).isEmpty();
    }

    @Test
    void assigningARoleStillJudgesItsNamedDepartments() {
        targetRole.setDepartmentGrant(json(
                "[{\"scopeType\":\"MANAGED_DEPARTMENTS\"},{\"scopeType\":\"DEPT_SUBTREE\",\"scopeExpr\":{\"deptId\":\"6\"}}]"));

        assertThat(assignment()).isPresent();
    }

    private Optional<String> assignment() {
        return ContextHolder.callWith(context, () -> ceiling.assignmentViolation(TARGET_ROLE));
    }

    private RoleGrantCeiling.Grants stored() {
        return ContextHolder.callWith(context, () -> ceiling.roleGrants(TARGET_ROLE));
    }

    private void save(RoleGrantCeiling.Grants before) {
        ContextHolder.runWith(context, () -> ceiling.assertWithinCaller(TARGET_ROLE, null, before));
    }

    private static Role role(long id, String grant) {
        Role role = new Role();
        role.setId(id);
        role.setDepartmentGrant(json(grant));
        return role;
    }

    private static RoleDataScope scope(long roleId) {
        RoleDataScope scope = new RoleDataScope();
        scope.setRoleId(roleId);
        scope.setModel(TICKET);
        scope.setDataScopes(json("[{\"scopeType\":\"ALL\"}]"));
        return scope;
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
