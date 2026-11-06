package io.softa.starter.permission.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.EmpInfo;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.index.EndpointIndex;
import io.softa.starter.permission.scope.DepartmentIdPathResolver;
import io.softa.starter.permission.scope.IdPath;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.RoleGrant;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The department grant's conditions worked out for the holder, and the exemption from it. What is
 * worth holding on to: the conditions widen one another and never a role without them; no employee
 * reaches nothing; and an exemption lets a role see past the grant without letting it change anything there.
 */
class DepartmentGrantConditionsTest {

    private static final String TICKET = "Ticket";
    private static final String MANAGER_RULE = "managerRule";

    private MockedStatic<ModelManager> modelManager;
    private EmpInfo employee;

    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel(TICKET)).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(ModelConstant.DEPARTMENT_MODEL, IdPath.FIELD))
                .thenReturn(mock(MetaField.class));
        MetaModel meta = mock(MetaModel.class);
        modelManager.when(() -> ModelManager.getModel(TICKET)).thenReturn(meta);
        MetaField department = mock(MetaField.class);
        when(department.getFieldType()).thenReturn(FieldType.MANY_TO_ONE);
        when(department.getRelatedModel()).thenReturn(ModelConstant.DEPARTMENT_MODEL);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(TICKET, ModelConstant.DEPARTMENT_FIELD))
                .thenReturn(department);
        employee = new EmpInfo();
        employee.setDeptId(7L);
        employee.setManagedDeptIds(Set.of(8L));
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    @DisplayName("my department and the departments I manage join the departments named")
    void conditionsJoinTheNamedDepartments() {
        String read = scope(role(Set.of("1/10"), Set.of("OWN_DEPARTMENT", "MANAGED_DEPARTMENTS"), Set.of()), AccessType.READ)
                .toString();

        assertThat(read).contains("\"1/10\"", "\"1/7\"", "\"1/8\"");
    }

    @Test
    @DisplayName("my department alone leaves out the departments I manage")
    void onlyTheConditionsHeldCount() {
        String read = scope(role(Set.of(), Set.of("OWN_DEPARTMENT"), Set.of()), AccessType.READ).toString();

        assertThat(read).contains("\"1/7\"").doesNotContain("\"1/8\"");
    }

    @Test
    @DisplayName("the reporting line is OR-ed with the departments, not AND-ed")
    void theReportingLineWidensTheDepartments() {
        Filters scope = scope(role(Set.of(), Set.of("MANAGED_DEPARTMENTS", "DIRECT_REPORTS"), Set.of()), AccessType.READ);

        assertThat(scope.toString()).contains("\"1/8\"", MANAGER_RULE);
        assertThat(scope.toString().toUpperCase()).contains("OR");
    }

    @Test
    @DisplayName("no employee behind the caller: a grant of only such conditions reaches nothing")
    void noEmployeeReachesNothing() {
        employee = null;

        Filters scope = scope(role(Set.of(), Set.of("OWN_DEPARTMENT", "MANAGED_DEPARTMENTS"), Set.of()), AccessType.READ);

        assertThat(scope).hasToString(ScopeRuleCompiler.matchNone().toString());
    }

    @Test
    @DisplayName("an exemption lifts the grant for viewing")
    void anExemptionLiftsViewing() {
        assertThat(scope(role(Set.of("1/10"), Set.of(), Set.of(TICKET)), AccessType.READ)).isNull();
        assertThat(scope(role(Set.of("1/10"), Set.of(), Set.of(TICKET)), AccessType.EXPORT)).isNull();
    }

    @Test
    @DisplayName("an exemption does not lift the grant for changing rows")
    void anExemptionLeavesChangesInsideTheGrant() {
        for (AccessType change : List.of(AccessType.CREATE, AccessType.UPDATE, AccessType.DELETE)) {
            assertThat(scope(role(Set.of("1/10"), Set.of(), Set.of(TICKET)), change).toString())
                    .as(change.name()).contains("\"1/10\"");
        }
    }

    @Test
    @DisplayName("an exemption on another model changes nothing here")
    void anExemptionIsPerModel() {
        assertThat(scope(role(Set.of("1/10"), Set.of(), Set.of("Other")), AccessType.READ).toString()).contains("\"1/10\"");
    }

    @Test
    @DisplayName("a snapshot cached before the conditions existed reads as having none")
    void aSnapshotWithoutTheFieldsReadsAsBefore() {
        RoleGrant old = role(Set.of("1/10"), null, null);

        assertThat(scope(old, AccessType.READ).toString()).contains("\"1/10\"");
    }

    private static RoleGrant role(Set<String> paths, Set<String> conditions, Set<String> exempt) {
        ScopeRule all = new ScopeRule();
        all.setScopeType(ScopeType.ALL);
        RoleGrant g = new RoleGrant();
        g.setRoleId(1L);
        g.setPermissions(new HashSet<>());
        Map<String, List<ScopeRule>> scopes = new HashMap<>();
        scopes.put(TICKET, List.of(all));
        g.setModelScopeMap(scopes);
        g.setModelScopeConditions(Map.of());
        g.setModelSensitiveFieldSetsMap(Map.of());
        g.setGrantedDepartmentPaths(paths);
        g.setDepartmentConditions(conditions);
        g.setDepartmentExemptModels(exempt);
        return g;
    }

    /** The role's rows on the model for {@code access}, the role being the only holder of the action. */
    @SuppressWarnings("unchecked")
    private Filters scope(RoleGrant grant, AccessType access) {
        PermissionInfo pi = new PermissionInfo();
        pi.setRoleGrants(List.of(grant));
        EndpointIndex index = mock(EndpointIndex.class);
        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor(TICKET)).thenReturn(Set.of(ScopeType.ALL, ScopeType.DIRECT_REPORTS));
        ScopeRuleCompiler compiler = mock(ScopeRuleCompiler.class);
        when(compiler.compile(anyList(), anyString())).thenAnswer(inv -> {
            List<ScopeRule> rules = inv.getArgument(0);
            return rules.getFirst().getScopeType() == ScopeType.ALL
                    ? null
                    : Filters.of(MANAGER_RULE, Operator.EQUAL, 2L);
        });
        DepartmentIdPathResolver paths = mock(DepartmentIdPathResolver.class);
        when(paths.idPathsOf(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> "1/" + id).toList();
        });
        PermissionServiceImpl service = new PermissionServiceImpl(null, compiler, null,
                (ModelService<Long>) mock(ModelService.class), applicability, () -> index,
                () -> null, () -> null, () -> paths);
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);
        ctx.setEmpInfo(employee);
        return ContextHolder.callWith(ctx, () -> service.departmentAxis(TICKET, grant, access));
    }
}
