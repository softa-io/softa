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
import tools.jackson.databind.JsonNode;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.index.EndpointIndex;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.sensitive.SensitiveFieldSetCache;
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
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import org.springframework.test.util.ReflectionTestUtils;


/**
 * A record a role reaches only as the target of a reference — never granted the model itself — shows its
 * label: id, display name, business key. Its other fields stay with the roles granted the model.
 *
 * <p>The employee view derives the lookups of the employee's relations, the user account among them, so a
 * manager may call the account endpoints; what those return is the account's label. The account view is
 * the account's own permission. EmpTimeProfile is owned by Employee and has no permission of its own.
 */
class LabelOnlyFieldPlanTest {

    private static final String EMPLOYEE_VIEW = "permission.employee.view";
    private static final String ACCOUNT_VIEW = "permission.user-account.view";
    private static final List<String> ACCOUNT_FIELDS = List.of("id", "nickname", "username", "mobile", "status");

    private PermissionInfo pi;
    private EndpointIndex index;
    private ModelService<Long> modelService;
    private PermissionServiceImpl service;
    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() {
        pi = new PermissionInfo();
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenAnswer(inv -> pi);

        index = mock(EndpointIndex.class);
        when(index.modelActionPermissions("Employee", "view")).thenReturn(Set.of(EMPLOYEE_VIEW));
        // The employee view reaches the account endpoints through its derived lookups...
        when(index.modelActionPermissions("UserAccount", "view")).thenReturn(Set.of(EMPLOYEE_VIEW, ACCOUNT_VIEW));
        // ...but only the account view was granted the account itself.
        when(index.directModelActionPermissions("Employee", "view")).thenReturn(Set.of(EMPLOYEE_VIEW));
        when(index.directModelActionPermissions("UserAccount", "view")).thenReturn(Set.of(ACCOUNT_VIEW));

        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor(anyString())).thenReturn(Set.of(ScopeType.ALL, ScopeType.CUSTOM));

        models = Mockito.mockStatic(ModelManager.class);
        model("UserAccount", ACCOUNT_FIELDS, List.of("nickname"));
        model("Employee", List.of("id", "name", "userId", "timeProfileId"), List.of("name"));
        model("EmpTimeProfile", List.of("id", "name", "weeklyHours"), List.of("name"));
        MetaField timeProfile = new MetaField();
        ReflectionTestUtils.setField(timeProfile, "fieldName", "timeProfileId");
        ReflectionTestUtils.setField(timeProfile, "fieldType", FieldType.ONE_TO_ONE);
        ReflectionTestUtils.setField(timeProfile, "relatedModel", "EmpTimeProfile");
        models.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(timeProfile));
        models.when(() -> ModelManager.getModelNames()).thenReturn(Set.of("UserAccount", "Employee", "EmpTimeProfile"));

        modelService = mockModelService();
        service = new PermissionServiceImpl(provider, stubCompiler(), null, modelService, applicability, () -> index);
    }

    private void model(String name, List<String> stored, List<String> displayName) {
        MetaModel meta = mock(MetaModel.class);
        when(meta.getLabel()).thenReturn(name);
        models.when(() -> ModelManager.existModel(name)).thenReturn(true);
        models.when(() -> ModelManager.getModel(name)).thenReturn(meta);
        models.when(() -> ModelManager.getModelStoredFields(name)).thenReturn(stored);
        models.when(() -> ModelManager.getModelDisplayName(name)).thenReturn(displayName);
        models.when(() -> ModelManager.getModelFields(name)).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    @SuppressWarnings("unchecked")
    private static ModelService<Long> mockModelService() {
        return mock(ModelService.class);
    }

    private static ScopeRuleCompiler stubCompiler() {
        ScopeRuleCompiler compiler = mock(ScopeRuleCompiler.class);
        when(compiler.compile(anyList(), anyString())).thenAnswer(inv -> {
            List<ScopeRule> rules = inv.getArgument(0);
            if (rules.stream().anyMatch(r -> r.getScopeType() == ScopeType.ALL)) return null;
            return Filters.of("companyId", Operator.EQUAL, 1L);
        });
        return compiler;
    }

    private static RoleGrant role(long id, Set<String> permissions, String model, ScopeType scope) {
        ScopeRule rule = new ScopeRule();
        rule.setScopeType(scope);
        RoleGrant g = new RoleGrant();
        g.setRoleId(id);
        g.setPermissions(new HashSet<>(permissions));
        g.setModelScopeMap(Map.of(model, List.of(rule)));
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

    private static Map<String, Object> account(long id) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("nickname", "Ann");
        row.put("username", "ann@example.com");
        row.put("mobile", "+6590000000");
        return row;
    }

    @Test
    void aRoleReachingAccountsOnlyThroughEmployeesSeesTheirLabel() {
        pi.setRoleGrants(List.of(role(1, Set.of(EMPLOYEE_VIEW), "Employee", ScopeType.ALL)));

        assertThat(as(() -> service.filterReadableFields("UserAccount", ACCOUNT_FIELDS, AccessType.READ)))
                .containsExactly("id", "nickname");
        List<Map<String, Object>> rows = new ArrayList<>(List.of(account(7)));
        as(() -> service.maskResponseValue("UserAccount", rows, AccessType.READ));
        assertThat(rows.getFirst()).containsEntry("nickname", "Ann").containsEntry("mobile", null).containsEntry("username", null);
    }

    @Test
    void aRoleGrantedTheAccountsSeesThemWhole() {
        pi.setRoleGrants(List.of(role(1, Set.of(ACCOUNT_VIEW), "UserAccount", ScopeType.ALL)));

        assertThat(as(() -> service.filterReadableFields("UserAccount", ACCOUNT_FIELDS, AccessType.READ)))
                .containsExactlyElementsOf(ACCOUNT_FIELDS);
    }

    @Test
    void anotherRolesAccountGrantShowsTheWholeRowOnlyOnItsOwnRows() {
        // Role 2 views accounts of company 1 only; role 1 reaches every account through employees. Taken
        // together by user, role 2 would have opened every account; by role it opens its own rows.
        pi.setRoleGrants(List.of(
                role(1, Set.of(EMPLOYEE_VIEW), "Employee", ScopeType.ALL),
                role(2, Set.of(ACCOUNT_VIEW), "UserAccount", ScopeType.CUSTOM)));
        when(modelService.getIds(eq("UserAccount"), any(Filters.class))).thenReturn(List.of(7L));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(account(7), account(8)));

        as(() -> service.maskResponseValue("UserAccount", rows, AccessType.READ));

        assertThat(rows.get(0)).containsEntry("mobile", "+6590000000");
        assertThat(rows.get(1)).containsEntry("mobile", null).containsEntry("nickname", "Ann");
    }

    @Test
    void aChildOwnedByAModelTheRoleViewsIsShownWhole() {
        pi.setRoleGrants(List.of(role(1, Set.of(EMPLOYEE_VIEW), "Employee", ScopeType.ALL)));

        assertThat(as(() -> service.filterReadableFields("EmpTimeProfile", List.of("id", "name", "weeklyHours"),
                AccessType.READ))).containsExactly("id", "name", "weeklyHours");
    }

    @Test
    void anOwnerNoRoleCanBeGrantedDoesNotOpenWhatItOwns() {
        // UserProfile owns the accounts and has no permission of its own. Left whole itself, it must not
        // lend that to the accounts: they are granted on their own, and the manager was not granted them.
        model("UserProfile", List.of("id", "fullName"), List.of("fullName"));
        MetaField accounts = new MetaField();
        ReflectionTestUtils.setField(accounts, "fieldName", "accounts");
        ReflectionTestUtils.setField(accounts, "fieldType", FieldType.ONE_TO_MANY);
        ReflectionTestUtils.setField(accounts, "relatedModel", "UserAccount");
        ReflectionTestUtils.setField(accounts, "relatedField", "profileId");
        models.when(() -> ModelManager.getModelFields("UserProfile")).thenReturn(List.of(accounts));
        models.when(() -> ModelManager.getModelNames())
                .thenReturn(Set.of("UserAccount", "Employee", "EmpTimeProfile", "UserProfile"));
        pi.setRoleGrants(List.of(role(1, Set.of(EMPLOYEE_VIEW), "Employee", ScopeType.ALL)));

        assertThat(as(() -> service.filterReadableFields("UserAccount", ACCOUNT_FIELDS, AccessType.READ)))
                .containsExactly("id", "nickname");
    }

    @Test
    void aModelGrantedOnItsOwnIsNotOpenedByAOneToManyFieldPointingAtIt() {
        // Employee.managedDepartments points at departments; departments are granted on their own.
        when(index.directModelActionPermissions("Department", "view")).thenReturn(Set.of("permission.department.view"));
        model("Department", List.of("id", "name", "costCentreId"), List.of("name"));
        MetaField managed = new MetaField();
        ReflectionTestUtils.setField(managed, "fieldName", "managedDepartments");
        ReflectionTestUtils.setField(managed, "fieldType", FieldType.ONE_TO_MANY);
        ReflectionTestUtils.setField(managed, "relatedModel", "Department");
        ReflectionTestUtils.setField(managed, "relatedField", "picEmpId");
        MetaField timeProfile = new MetaField();
        ReflectionTestUtils.setField(timeProfile, "fieldName", "timeProfileId");
        ReflectionTestUtils.setField(timeProfile, "fieldType", FieldType.ONE_TO_ONE);
        ReflectionTestUtils.setField(timeProfile, "relatedModel", "EmpTimeProfile");
        models.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(timeProfile, managed));
        models.when(() -> ModelManager.getModelNames())
                .thenReturn(Set.of("UserAccount", "Employee", "EmpTimeProfile", "Department"));
        pi.setRoleGrants(List.of(role(1, Set.of(EMPLOYEE_VIEW), "Employee", ScopeType.ALL)));

        assertThat(as(() -> service.filterReadableFields("Department", List.of("id", "name", "costCentreId"),
                AccessType.READ))).containsExactly("id", "name");
    }

    @Test
    void aConditionPastTheLabelMatchesNothing() {
        pi.setRoleGrants(List.of(role(1, Set.of(EMPLOYEE_VIEW), "Employee", ScopeType.ALL)));

        Filters guarded = as(() -> service.appendScopeAccessFilters("UserAccount",
                Filters.of("mobile", Operator.EQUAL, "+6590000000")));
        assertThat(guarded.toString()).doesNotContain("+6590000000");
    }
}
