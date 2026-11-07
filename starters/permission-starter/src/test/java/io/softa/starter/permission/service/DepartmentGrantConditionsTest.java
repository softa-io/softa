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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.context.EmpInfo;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.RecordAccess;
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
import io.softa.starter.permission.spi.PermissionSnapshotProvider;
import io.softa.starter.permission.spi.RoleGrant;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    @DisplayName("record access offers an exempt role what it may change, not everything it may see")
    @SuppressWarnings("unchecked")
    void recordAccessJudgesChangesInsideTheGrant() {
        String view = "permission.ticket.view";
        String update = "permission.ticket.update";
        RoleGrant grant = role(Set.of("1/10"), Set.of(), Set.of(TICKET));
        grant.setPermissions(new HashSet<>(Set.of(view, update)));
        PermissionInfo pi = new PermissionInfo();
        pi.setRoleGrants(List.of(grant));
        pi.setPermissions(Set.of(view, update));
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenReturn(pi);
        EndpointIndex index = mock(EndpointIndex.class);
        when(index.modelActionPermissions(TICKET, "view")).thenReturn(Set.of(view));
        when(index.modelActionPermissions(TICKET, "update")).thenReturn(Set.of(update));
        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor(TICKET)).thenReturn(Set.of(ScopeType.ALL));
        ScopeRuleCompiler compiler = mock(ScopeRuleCompiler.class);
        ModelService<Long> modelService = (ModelService<Long>) mock(ModelService.class);
        // The ticket sits outside the granted department: any filter naming the department misses it.
        when(modelService.getIds(eq(TICKET), any(Filters.class))).thenAnswer(inv ->
                inv.getArgument(1).toString().contains("\"1/10\"") ? List.of() : List.of(1L));
        PermissionServiceImpl service = new PermissionServiceImpl(provider, compiler, null, modelService,
                applicability, () -> index);
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);

        List<RecordAccess> access = ContextHolder.callWith(ctx, () -> service.getRecordAccess(TICKET, List.of(1L)));

        assertThat(access.getFirst().actions()).doesNotContain(AccessType.UPDATE);
    }

    @Test
    @DisplayName("an exemption lets a role view past the grant, not export past it")
    void anExemptionDoesNotReachAnExport() {
        RoleGrant exempt = role(Set.of("1/10"), Set.of(), Set.of(TICKET));

        assertThat(scope(exempt, AccessType.READ)).isNull();
        assertThat(scope(exempt, AccessType.EXPORT).toString()).contains("1/10");
        assertThat(scope(exempt, AccessType.UPDATE).toString()).contains("1/10");
    }

    @Test
    @DisplayName("what a role views and what it exports on an exempt model are remembered apart")
    @SuppressWarnings("unchecked")
    void viewingAndExportingAreRememberedApart() {
        RoleGrant exempt = role(Set.of("1/10"), Set.of(), Set.of(TICKET));
        PermissionInfo pi = new PermissionInfo();
        pi.setRoleGrants(List.of(exempt));
        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor(TICKET)).thenReturn(Set.of(ScopeType.ALL));
        ScopeRuleCompiler compiler = mock(ScopeRuleCompiler.class);
        EndpointIndex index = mock(EndpointIndex.class);
        PermissionServiceImpl service = new PermissionServiceImpl(null, compiler, null,
                (ModelService<Long>) mock(ModelService.class), applicability, () -> index);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            assertThat(service.rowScope(pi, TICKET, AccessType.READ)).isNull();
            assertThat(service.rowScope(pi, TICKET, AccessType.EXPORT)).isNotNull();
            assertThat(service.rowScope(pi, TICKET, AccessType.READ)).isNull();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("a batch write reads the holder's department paths once, past the checks that forget a grant's rows")
    @SuppressWarnings("unchecked")
    void aRequestResolvesTheHoldersPathsOnce() {
        RoleGrant grant = role(Set.of("1/10"), Set.of("OWN_DEPARTMENT"), Set.of());
        PermissionInfo pi = new PermissionInfo();
        pi.setRoleGrants(List.of(grant));
        EndpointIndex index = mock(EndpointIndex.class);
        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor(TICKET)).thenReturn(Set.of(ScopeType.ALL));
        DepartmentIdPathResolver paths = mock(DepartmentIdPathResolver.class);
        when(paths.idPathsOf(anyCollection())).thenReturn(List.of("1/7"));
        PermissionServiceImpl service = new PermissionServiceImpl(null, mock(ScopeRuleCompiler.class), null,
                (ModelService<Long>) mock(ModelService.class), applicability, () -> index,
                () -> null, () -> null, () -> paths);
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);
        ctx.setEmpInfo(employee);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            ContextHolder.callWith(ctx, () -> {
                for (int i = 0; i < 3; i++) {
                    assertThat(service.departmentAxis(TICKET, grant, AccessType.UPDATE).toString()).contains("1/7");
                    // What a write check does before reading anything.
                    service.checkIdsAccess(TICKET, List.of(), AccessType.UPDATE);
                }
                return null;
            });
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        org.mockito.Mockito.verify(paths, org.mockito.Mockito.times(1)).idPathsOf(anyCollection());
    }

    // ---- a department picker on a record being written ---------------------------------------------

    private static final String EDIT_TICKET = "permission.ticket.edit";
    /** A model belonging to a department that has no manager to judge direct reports by. */
    private static final String NO_MANAGER = "DepartmentNote";

    /** The scope a picker gets, with these roles and the ticket's update held by {@code EDIT_TICKET}. */
    @SuppressWarnings("unchecked")
    private Filters writable(String model, RoleGrant... roles) {
        modelManager.when(() -> ModelManager.existModel(ModelConstant.DEPARTMENT_MODEL)).thenReturn(true);
        PermissionInfo pi = new PermissionInfo();
        pi.setRoleGrants(List.of(roles));
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenReturn(pi);
        EndpointIndex index = mock(EndpointIndex.class);
        when(index.modelActionPermissions(TICKET, "update")).thenReturn(Set.of(EDIT_TICKET));
        when(index.modelActionPermissions(RECORD, "update")).thenReturn(Set.of(EDIT_TICKET));
        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor(ModelConstant.DEPARTMENT_MODEL)).thenReturn(Set.of(ScopeType.ALL));
        when(applicability.applicableFor(TICKET)).thenReturn(Set.of(ScopeType.ALL, ScopeType.DIRECT_REPORTS));
        when(applicability.applicableFor(NO_MANAGER)).thenReturn(Set.of(ScopeType.ALL));
        when(index.modelActionPermissions(NO_MANAGER, "update")).thenReturn(Set.of(EDIT_TICKET));
        DepartmentIdPathResolver paths = mock(DepartmentIdPathResolver.class);
        when(paths.idPathsOf(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> "1/" + id).toList();
        });
        PermissionServiceImpl service = new PermissionServiceImpl(provider, mock(ScopeRuleCompiler.class), null,
                (ModelService<Long>) mock(ModelService.class), applicability, () -> index,
                () -> null, () -> null, () -> paths);
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);
        ctx.setEmpInfo(employee);
        return ContextHolder.callWith(ctx, () -> service.getWritableDepartmentScope(model, AccessType.UPDATE));
    }

    private static RoleGrant writer(Set<String> paths, Set<String> conditions, Set<String> exempt) {
        RoleGrant g = role(paths, conditions, exempt);
        g.setPermissions(Set.of(EDIT_TICKET));
        return g;
    }

    @Test
    @DisplayName("a picker offers the writers' departments, past no exemption")
    void aPickerOffersTheWritersDepartments() {
        String offered = writable(TICKET, writer(Set.of("1/10"), Set.of("OWN_DEPARTMENT"), Set.of(TICKET))).toString();

        assertThat(offered).contains("\"idPath\"", "1/10", "1/7").doesNotContain("departmentId");
    }

    @Test
    @DisplayName("a role that may only read the model lends the picker nothing")
    void aReaderLendsThePickerNothing() {
        RoleGrant reader = role(Set.of("1/99"), Set.of(), Set.of());

        assertThat(writable(TICKET, writer(Set.of("1/10"), Set.of(), Set.of()), reader).toString())
                .contains("1/10").doesNotContain("1/99");
    }

    @Test
    @DisplayName("a writer with no department grant leaves the picker as it reads")
    void anUnboundedWriterLeavesThePicker() {
        assertThat(writable(TICKET, writer(Set.of("1/10"), Set.of(), Set.of()), writer(null, Set.of(), Set.of()))).isNull();
    }

    @Test
    @DisplayName("a writer granted no department offers none")
    void aWriterGrantedNoneOffersNone() {
        assertThat(writable(TICKET, writer(Set.of(), Set.of(), Set.of())).toString()).contains("\"IN\",[]");
    }

    @Test
    @DisplayName("a writer with direct reports leaves the picker to the save, which is the one that can tell")
    void aWriterWithDirectReportsLeavesThePicker() {
        assertThat(writable(TICKET, writer(Set.of(), Set.of("DIRECT_REPORTS"), Set.of()))).isNull();
        // However much else the role names, and whichever writer it is.
        assertThat(writable(TICKET, writer(Set.of("1/10"), Set.of("OWN_DEPARTMENT", "DIRECT_REPORTS"), Set.of()))).isNull();
        assertThat(writable(TICKET, writer(Set.of("1/10"), Set.of(), Set.of()),
                writer(Set.of(), Set.of("DIRECT_REPORTS"), Set.of()))).isNull();
    }

    @Test
    @DisplayName("direct reports on a role that may only read lend the picker nothing")
    void aReaderWithDirectReportsLendsNothing() {
        assertThat(writable(TICKET, writer(Set.of("1/10"), Set.of(), Set.of()),
                role(Set.of(), Set.of("DIRECT_REPORTS"), Set.of())).toString()).contains("1/10");
    }

    @Test
    @DisplayName("where direct reports do not apply the save judges by departments, and so does the picker")
    void directReportsThatDoNotApplyChangeNothing() {
        modelManager.when(() -> ModelManager.existModel(NO_MANAGER)).thenReturn(true);
        MetaField department = toOne(ModelConstant.DEPARTMENT_MODEL);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(NO_MANAGER, ModelConstant.DEPARTMENT_FIELD))
                .thenReturn(department);

        assertThat(writable(NO_MANAGER, writer(Set.of(), Set.of("DIRECT_REPORTS"), Set.of())).toString())
                .contains("\"IN\",[]");
        assertThat(writable(NO_MANAGER, writer(Set.of("1/10"), Set.of("DIRECT_REPORTS"), Set.of())).toString())
                .contains("1/10");
    }

    @Test
    @DisplayName("a department picked on a record following its employee places nothing, so it is not narrowed")
    void aRecordFollowingItsEmployeeIsNotNarrowed() {
        recordOfAnEmployee();

        assertThat(writable(RECORD, writer(Set.of("1/10"), Set.of(), Set.of()))).isNull();
    }

    // ---- a record belonging to an employee -----------------------------------------------------------

    private static final String RECORD = "EmpFamily";
    private static final String THROUGH_EMPLOYEE = "employeeId.departmentId.idPath";

    /** {@code EmpFamily.employeeId} onto the employee model, which belongs to a department. */
    private void recordOfAnEmployee() {
        modelManager.when(() -> ModelManager.existModel(RECORD)).thenReturn(true);
        MetaModel meta = mock(MetaModel.class);
        MetaField employee = toOne(ModelConstant.EMPLOYEE_MODEL);
        MetaField department = toOne(ModelConstant.DEPARTMENT_MODEL);
        modelManager.when(() -> ModelManager.getModel(RECORD)).thenReturn(meta);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(RECORD, ModelConstant.EMPLOYEE_FIELD)).thenReturn(employee);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(ModelConstant.EMPLOYEE_MODEL, ModelConstant.DEPARTMENT_FIELD))
                .thenReturn(department);
    }

    private static MetaField toOne(String related) {
        MetaField field = mock(MetaField.class);
        when(field.getFieldType()).thenReturn(FieldType.MANY_TO_ONE);
        when(field.getRelatedModel()).thenReturn(related);
        return field;
    }

    /** The role, holding rules of its own on the record, so it is not following anyone. */
    private static RoleGrant roleOnRecord(Set<String> paths, Set<String> conditions, Set<String> exempt) {
        RoleGrant g = role(paths, conditions, exempt);
        ScopeRule all = new ScopeRule();
        all.setScopeType(ScopeType.ALL);
        g.setModelScopeMap(Map.of(RECORD, List.of(all)));
        return g;
    }

    @Test
    @DisplayName("named departments and conditions reach a record through its employee")
    void conditionsReachARecordThroughItsEmployee() {
        recordOfAnEmployee();

        String read = scope(RECORD, roleOnRecord(Set.of("1/10"), Set.of("OWN_DEPARTMENT", "MANAGED_DEPARTMENTS"), Set.of()),
                AccessType.READ).toString();

        assertThat(read).contains(THROUGH_EMPLOYEE, "1/10", "1/7", "1/8");
    }

    @Test
    @DisplayName("direct reports reach a record through its employee where the type applies")
    void directReportsReachARecord() {
        recordOfAnEmployee();

        String read = scope(RECORD, roleOnRecord(Set.of(), Set.of("DIRECT_REPORTS"), Set.of()), AccessType.READ).toString();

        assertThat(read).contains(MANAGER_RULE);
    }

    @Test
    @DisplayName("an exemption on the record lifts the grant for viewing it only")
    void anExemptionOnTheRecordLiftsViewingOnly() {
        recordOfAnEmployee();
        RoleGrant exempt = roleOnRecord(Set.of("1/10"), Set.of(), Set.of(RECORD));

        assertThat(scope(RECORD, exempt, AccessType.READ)).isNull();
        assertThat(scope(RECORD, exempt, AccessType.UPDATE).toString()).contains(THROUGH_EMPLOYEE);
    }

    /** The employee model owning the record through a to-many, and the role holding a rule on the employee. */
    private RoleGrant roleFollowingTheEmployee(Set<String> exempt) {
        recordOfAnEmployee();
        modelManager.when(() -> ModelManager.existModel(ModelConstant.EMPLOYEE_MODEL)).thenReturn(true);
        MetaModel employeeMeta = mock(MetaModel.class);
        modelManager.when(() -> ModelManager.getModel(ModelConstant.EMPLOYEE_MODEL)).thenReturn(employeeMeta);
        MetaField children = mock(MetaField.class);
        when(children.getFieldType()).thenReturn(FieldType.ONE_TO_MANY);
        when(children.getRelatedModel()).thenReturn(RECORD);
        when(children.getRelatedField()).thenReturn(ModelConstant.EMPLOYEE_FIELD);
        modelManager.when(() -> ModelManager.getModelFields(ModelConstant.EMPLOYEE_MODEL)).thenReturn(List.of(children));
        RoleGrant g = role(Set.of("1/10"), Set.of(), exempt);
        ScopeRule all = new ScopeRule();
        all.setScopeType(ScopeType.ALL);
        g.setModelScopeMap(Map.of(ModelConstant.EMPLOYEE_MODEL, List.of(all)));
        return g;
    }

    @Test
    @DisplayName("a sub-record the role reaches only through the employee is judged by the employee")
    void aFollowerIsJudgedByItsEmployee() {
        // The employee's own scope carries the grant, or its exemption: a person shown past the grant keeps
        // their sections, and one within it is not bounded twice.
        assertThat(scope(RECORD, roleFollowingTheEmployee(Set.of()), AccessType.READ)).isNull();
        assertThat(scope(RECORD, roleFollowingTheEmployee(Set.of(ModelConstant.EMPLOYEE_MODEL)), AccessType.READ)).isNull();
    }

    @Test
    @DisplayName("a role with no way to the record through the employee is bounded on it directly")
    void aRecordFollowingNoEmployeeIsBounded() {
        recordOfAnEmployee();

        assertThat(scope(RECORD, role(Set.of("1/10"), Set.of(), Set.of()), AccessType.READ).toString())
                .contains(THROUGH_EMPLOYEE);
    }

    @Test
    @DisplayName("work a service does past the permission checks is not bounded")
    void workPastTheChecksIsNotBounded() {
        recordOfAnEmployee();
        PermissionServiceImpl service = new PermissionServiceImpl(null, null, null, null, null);
        Filters original = Filters.of("name", Operator.EQUAL, "x");
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);

        Filters seen = ContextHolder.callWith(ctx, () -> ContextUtils.withoutPermissionChecks(
                () -> service.appendScopeAccessFilters(RECORD, original)));

        assertThat(seen).isEqualTo(original);
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
        return scope(TICKET, grant, access);
    }

    @SuppressWarnings("unchecked")
    private Filters scope(String model, RoleGrant grant, AccessType access) {
        PermissionInfo pi = new PermissionInfo();
        pi.setRoleGrants(List.of(grant));
        EndpointIndex index = mock(EndpointIndex.class);
        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor(model)).thenReturn(Set.of(ScopeType.ALL, ScopeType.DIRECT_REPORTS));
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
        return ContextHolder.callWith(ctx, () -> service.departmentAxis(model, grant, access));
    }
}
