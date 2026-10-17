package io.softa.starter.permission.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.AccessScope;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.index.EndpointIndex;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;
import io.softa.starter.permission.spi.RoleGrant;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A condition through a relation reads the related row. Joined as it is, with no range of its own, it
 * would answer questions about related rows the caller cannot read — so a condition past the related
 * row's label, on a model the caller reads under a range, is held to the related rows inside that range.
 */
class RelatedConditionScopeTest {

    private static final String VIEW = "permission.employee.view";
    private static final Filters MY_DEPARTMENTS = Filters.of("departmentId", Operator.IN, List.of(10L));

    private PermissionInfo pi;
    private ModelService<Long> modelService;
    private PermissionServiceImpl service;
    private MockedStatic<ModelManager> models;
    /** The action each related read ran as. */
    private final List<AccessType> readAs = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pi = new PermissionInfo();
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenAnswer(inv -> pi);
        EndpointIndex index = mock(EndpointIndex.class);
        when(index.modelActionPermissions("Employee", "view")).thenReturn(Set.of(VIEW));
        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor(anyString()))
                .thenReturn(Set.of(ScopeType.ALL, ScopeType.MANAGED_DEPARTMENTS));
        ScopeRuleCompiler compiler = mock(ScopeRuleCompiler.class);
        when(compiler.compile(anyList(), anyString())).thenAnswer(inv -> {
            List<ScopeRule> rules = inv.getArgument(0);
            return rules.stream().anyMatch(r -> r.getScopeType() == ScopeType.ALL) ? null : MY_DEPARTMENTS;
        });
        modelService = mock(ModelService.class);
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenAnswer(inv -> {
            readAs.add(AccessScope.current());
            return List.of(7L);
        });
        service = new PermissionServiceImpl(provider, compiler, null, modelService, applicability, () -> index);

        models = Mockito.mockStatic(ModelManager.class);
        relation("Employee", "managerId", "Employee");
        relation("LeaveRequest", "employeeId", "Employee");
        alias("Employee", "managerBirth", "managerId.dateOfBirth");
        models.when(() -> ModelManager.getModelDisplayName("Employee")).thenReturn(List.of("name"));
        MetaModel employee = mock(MetaModel.class);
        when(employee.getSearchName()).thenReturn(List.of("code", "managerBirth"));
        models.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
        models.when(() -> ModelManager.getModel("Employee")).thenReturn(employee);
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    private void relation(String model, String field, String related) {
        MetaField f = new MetaField();
        ReflectionTestUtils.setField(f, "fieldName", field);
        ReflectionTestUtils.setField(f, "fieldType", FieldType.MANY_TO_ONE);
        ReflectionTestUtils.setField(f, "relatedModel", related);
        models.when(() -> ModelManager.existField(model, field)).thenReturn(true);
        models.when(() -> ModelManager.getModelField(model, field)).thenReturn(f);
    }

    private void alias(String model, String field, String cascadedField) {
        MetaField f = new MetaField();
        ReflectionTestUtils.setField(f, "fieldName", field);
        ReflectionTestUtils.setField(f, "fieldType", FieldType.DATE);
        ReflectionTestUtils.setField(f, "cascadedField", cascadedField);
        models.when(() -> ModelManager.existField(model, field)).thenReturn(true);
        models.when(() -> ModelManager.getModelField(model, field)).thenReturn(f);
    }

    /** The caller reads employees of their own departments only. */
    private void readEmployeesOfMyDepartments() {
        pi.setRoleGrants(List.of(role(ScopeType.MANAGED_DEPARTMENTS)));
    }

    private static RoleGrant role(ScopeType scope) {
        RoleGrant g = new RoleGrant();
        g.setRoleId(1L);
        g.setPermissions(new HashSet<>(Set.of(VIEW)));
        ScopeRule rule = new ScopeRule();
        rule.setScopeType(scope);
        Map<String, List<ScopeRule>> scopes = new HashMap<>();
        scopes.put("Employee", List.of(rule));
        g.setModelScopeMap(scopes);
        g.setModelScopeConditions(new HashMap<>());
        g.setModelSensitiveFieldSetsMap(Map.of());
        return g;
    }

    private <T> T as(java.util.function.Supplier<T> call) {
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);
        return ContextHolder.callWith(ctx, call::get);
    }

    private Filters conditionOn(String model, Filters filters) {
        return as(() -> service.appendScopeAccessFiltersIgnoringRowScope(model, filters));
    }

    @Test
    @DisplayName("a condition past a related row's label is held to the related rows inside the range")
    void aConditionOnARelatedRowsValueIsHeldToTheRange() {
        readEmployeesOfMyDepartments();

        Filters bounded = conditionOn("LeaveRequest",
                Filters.of("employeeId.dateOfBirth", Operator.LESS_THAN, "1980-01-01"));

        assertThat(bounded).isEqualTo(Filters.of("employeeId", Operator.IN, List.of(7L)));
        ArgumentCaptor<Filters> asked = ArgumentCaptor.forClass(Filters.class);
        verify(modelService).getIds(eq("Employee"), asked.capture());
        assertThat(asked.getValue()).isEqualTo(Filters.of("dateOfBirth", Operator.LESS_THAN, "1980-01-01"));
    }

    @Test
    @DisplayName("a related row the range reaches none of matches nothing")
    void noRelatedRowInRangeMatchesNothing() {
        readEmployeesOfMyDepartments();
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenReturn(List.of());

        assertThat(conditionOn("LeaveRequest", Filters.of("employeeId.dateOfBirth", Operator.IS_SET, null)))
                .isEqualTo(ScopeRuleCompiler.matchNone());
    }

    @Test
    @DisplayName("IS NOT SET still matches a row with no related row")
    void isNotSetKeepsTheRowsWithoutARelatedRow() {
        readEmployeesOfMyDepartments();

        Filters bounded = conditionOn("LeaveRequest", Filters.of("employeeId.dateOfBirth", Operator.IS_NOT_SET, null));

        assertThat(bounded.toString()).contains("employeeId", "IS NOT SET", "IN", "7");
        assertThat(bounded.toString()).doesNotContain("dateOfBirth");
    }

    @Test
    @DisplayName("a condition on what the reference already shows is left as it is")
    void aConditionOnTheLabelIsLeftAlone() {
        readEmployeesOfMyDepartments();
        Filters byName = Filters.of("employeeId.name", Operator.EQUAL, "Ada");

        assertThat(conditionOn("LeaveRequest", byName)).isEqualTo(byName);
        verify(modelService, never()).getIds(anyString(), any(Filters.class));
    }

    @Test
    @DisplayName("a subtree filter on a reference keeps walking the tree")
    void aSubtreeFilterIsLeftAlone() {
        readEmployeesOfMyDepartments();
        Filters underTwelve = Filters.of("employeeId.idPath", Operator.START_WITH, "1/12/");

        assertThat(conditionOn("LeaveRequest", underTwelve)).isEqualTo(underTwelve);
    }

    @Test
    @DisplayName("a related model the caller reads without a range is left as it is")
    void aRelatedModelWithoutARangeIsLeftAlone() {
        pi.setRoleGrants(List.of(role(ScopeType.ALL)));
        Filters byBirth = Filters.of("employeeId.dateOfBirth", Operator.LESS_THAN, "1980-01-01");

        assertThat(conditionOn("LeaveRequest", byBirth)).isEqualTo(byBirth);
    }

    @Test
    @DisplayName("a declared cascaded field is judged by the path it stands for")
    void aDeclaredCascadedFieldIsJudgedByItsPath() {
        readEmployeesOfMyDepartments();

        assertThat(conditionOn("Employee", Filters.of("managerBirth", Operator.LESS_THAN, "1980-01-01")))
                .isEqualTo(Filters.of("managerId", Operator.IN, List.of(7L)));
    }

    @Test
    @DisplayName("searchName is judged by the fields it searches")
    void searchNameIsJudgedByItsFields() {
        readEmployeesOfMyDepartments();

        Filters bounded = conditionOn("Employee", Filters.of("searchName", Operator.CONTAINS, "19"));

        assertThat(bounded.toString()).contains("code", "managerId", "IN", "7");
        assertThat(bounded.toString()).doesNotContain("searchName", "managerBirth", "dateOfBirth");
    }

    @Test
    @DisplayName("the related rows are read as a read, whatever the outer read is performed for")
    void theRelatedRowsAreReadAsARead() {
        readEmployeesOfMyDepartments();

        as(() -> AccessScope.callAs(AccessType.EXPORT, () -> service.appendScopeAccessFiltersIgnoringRowScope(
                "LeaveRequest", Filters.of("employeeId.dateOfBirth", Operator.LESS_THAN, "1980-01-01"))));

        assertThat(readAs).containsExactly(AccessType.READ);
    }

    @Test
    @DisplayName("a sort past a related row's label is dropped, a sort by its label kept")
    void aSortPastTheLabelIsDropped() {
        readEmployeesOfMyDepartments();
        FlexQuery query = new FlexQuery();
        query.setOrders(Orders.ofAsc("employeeId.dateOfBirth").addAsc("employeeId.name"));

        as(() -> {
            service.guardQuery("LeaveRequest", query);
            return null;
        });

        assertThat(query.getOrders().getFields()).containsExactly("employeeId.name");
    }

    @Test
    @DisplayName("a grouping past a related row's label is refused")
    void aGroupingPastTheLabelIsRefused() {
        readEmployeesOfMyDepartments();
        FlexQuery query = new FlexQuery();
        query.setGroupBy(List.of("employeeId.dateOfBirth"));

        assertThatThrownBy(() -> as(() -> {
            service.guardQuery("LeaveRequest", query);
            return null;
        })).isInstanceOf(PermissionException.class);
    }
}
