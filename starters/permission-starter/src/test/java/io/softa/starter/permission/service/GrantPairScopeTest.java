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
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
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
 * Each role applies on its own: an action reaches the rows of the roles holding it, not the merged
 * rows of every role the user has.
 *
 * <p>Three roles a department HR might hold — R1 views and edits their departments, R2 views and edits
 * the permanent staff of their departments, R3 is an all-staff directory that only views.
 */
class GrantPairScopeTest {

    private static final long TENANT = 1L;
    private static final long USER = 2L;

    private static final String VIEW = "permission.employee.view";
    private static final String UPDATE = "permission.employee.update";
    private static final String EXPORT = "permission.employee.export";

    /** What the compiler stub renders a department rule as. */
    private static final Filters MY_DEPARTMENTS = Filters.of("departmentId", Operator.IN, List.of(10L));
    private static final String NOT_CONTRACTOR =
            "[[\"employmentType\",\"!=\",\"Contractor\"]]";

    private PermissionInfo pi;
    private ModelService<Long> modelService;
    private PermissionServiceImpl service;
    private EndpointIndex index;

    @BeforeEach
    void setUp() {
        pi = new PermissionInfo();
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenAnswer(inv -> pi);

        index = mock(EndpointIndex.class);
        when(index.modelActionPermissions("Employee", "view")).thenReturn(Set.of(VIEW));
        when(index.modelActionPermissions("Employee", "update")).thenReturn(Set.of(UPDATE));
        when(index.actionPermissions("Employee", "export")).thenReturn(Set.of(EXPORT));

        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor("Employee"))
                .thenReturn(Set.of(ScopeType.ALL, ScopeType.CUSTOM, ScopeType.MANAGED_DEPARTMENTS));

        modelService = mockModelService();
        service = new PermissionServiceImpl(provider, stubCompiler(), null, modelService, applicability,
                () -> index);
    }

    @SuppressWarnings("unchecked")
    private static ModelService<Long> mockModelService() {
        return mock(ModelService.class);
    }

    /** ALL → no restriction; a department rule → {@link #MY_DEPARTMENTS}; CUSTOM → its own Filters. */
    private static ScopeRuleCompiler stubCompiler() {
        ScopeRuleCompiler compiler = mock(ScopeRuleCompiler.class);
        when(compiler.compile(anyList(), anyString())).thenAnswer(inv -> {
            List<ScopeRule> rules = inv.getArgument(0);
            if (rules.stream().anyMatch(r -> r.getScopeType() == ScopeType.ALL)) {
                return null;
            }
            List<Filters> parts = new ArrayList<>();
            for (ScopeRule r : rules) {
                parts.add(r.getScopeType() == ScopeType.CUSTOM
                        ? Filters.of(r.getScopeExpr().toString())
                        : MY_DEPARTMENTS);
            }
            return parts.size() == 1 ? parts.getFirst() : Filters.or(parts.get(0), parts.get(1));
        });
        return compiler;
    }

    private static ScopeRule rule(ScopeType type) {
        ScopeRule r = new ScopeRule();
        r.setScopeType(type);
        return r;
    }

    private static RoleGrant role(long id, Set<String> permissions, ScopeType scope, String condition,
                                  Set<Long> companies) {
        RoleGrant g = new RoleGrant();
        g.setRoleId(id);
        g.setPermissions(new HashSet<>(permissions));
        Map<String, List<ScopeRule>> scopes = new HashMap<>();
        scopes.put("Employee", List.of(rule(scope)));
        g.setModelScopeMap(scopes);
        Map<String, JsonNode> conditions = new HashMap<>();
        if (condition != null) {
            conditions.put("Employee", JsonUtils.stringToObject(condition, JsonNode.class));
        }
        g.setModelScopeConditions(conditions);
        g.setModelSensitiveFieldSetsMap(Map.of());
        g.setGrantedCompanyIds(companies);
        return g;
    }

    /** R1 department HR, R2 department HR for permanent staff, R3 the all-staff directory. */
    private void holdTheExampleRoles() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, null, null),
                role(2, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, NOT_CONTRACTOR, null),
                role(3, Set.of(VIEW), ScopeType.ALL, null, null)));
    }

    private Filters scope(AccessType access) {
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        ctx.setUserId(USER);
        return ContextHolder.callWith(ctx, () -> AccessScope.callAs(access,
                () -> service.appendScopeAccessFilters("Employee", new Filters())));
    }

    private void checkIds(AccessType access, List<Long> ids) {
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        ctx.setUserId(USER);
        ContextHolder.runWith(ctx, () -> service.checkIdsAccess("Employee", ids, access));
    }

    @Test
    @DisplayName("viewing reaches every row, through the directory role")
    void viewingReachesTheUnionOfTheViewingRoles() {
        holdTheExampleRoles();

        assertThat(Filters.isEmpty(scope(AccessType.READ))).isTrue();
    }

    @Test
    @DisplayName("editing reaches only the departments — the directory role does not edit")
    void editingReachesOnlyTheRolesThatEdit() {
        holdTheExampleRoles();

        String update = scope(AccessType.UPDATE).toString();

        assertThat(update).contains("departmentId", "10", "employmentType");
    }

    @Test
    @DisplayName("an id outside the editing roles' rows is refused, though a viewing role reaches it")
    void anUpdateOutsideTheEditingRolesIsRefused() {
        holdTheExampleRoles();
        when(modelService.count(eq("Employee"), any(Filters.class))).thenReturn(0L);

        assertThatThrownBy(() -> checkIds(AccessType.UPDATE, List.of(99L)))
                .isInstanceOf(PermissionException.class);
    }

    @Test
    @DisplayName("a read by id is not counted at all when a viewing role reaches every row")
    void aReadByIdUnderAnUnrestrictedViewerNeedsNoCount() {
        holdTheExampleRoles();

        checkIds(AccessType.READ, List.of(99L));

        verify(modelService, never()).count(anyString(), any(Filters.class));
    }

    @Test
    @DisplayName("the condition is AND-ed onto its own role's rules only")
    void theConditionNarrowsOnlyItsOwnRole() {
        pi.setRoleGrants(List.of(role(2, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, NOT_CONTRACTOR, null)));

        Filters update = scope(AccessType.UPDATE);

        assertThat(update.toString()).contains("departmentId", "employmentType", "Contractor");
    }

    @Test
    @DisplayName("an action no role holds falls back to every role, as before actions had scopes")
    void anActionNobodyHoldsKeepsTheMergedRows() {
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW), ScopeType.ALL, null, null)));

        // Nothing grants update: whatever reached this read was authorized elsewhere.
        assertThat(Filters.isEmpty(scope(AccessType.UPDATE))).isTrue();
    }

    @Test
    @DisplayName("an export reaches the rows of the roles that may export")
    void anExportReachesOnlyTheExportingRoles() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, EXPORT), ScopeType.MANAGED_DEPARTMENTS, null, null),
                role(3, Set.of(VIEW), ScopeType.ALL, null, null)));

        assertThat(scope(AccessType.EXPORT).toString()).contains("departmentId", "10");
        assertThat(Filters.isEmpty(scope(AccessType.READ))).isTrue();
    }

    @Test
    @DisplayName("an export of a model whose export permission nobody holds reaches no row")
    void anExportNobodyHoldsReachesNothing() {
        // The shared export endpoints let this user through on another model's export permission.
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW, "permission.department.export"), ScopeType.ALL, null, null)));

        assertThat(scope(AccessType.EXPORT)).isEqualTo(ScopeRuleCompiler.matchNone());
        assertThat(Filters.isEmpty(scope(AccessType.READ))).isTrue();
    }

    @Test
    @DisplayName("an export of a model with no export permission registered keeps every role's rows")
    void anExportOfAModelWithoutAnExportPermissionKeepsTheMergedRows() {
        when(index.actionPermissions("Employee", "export")).thenReturn(Set.of());
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW), ScopeType.ALL, null, null)));

        assertThat(Filters.isEmpty(scope(AccessType.EXPORT))).isTrue();
    }

    @Test
    @DisplayName("a role whose permission lists only a bulk update still holds update")
    void aHandListedBulkUpdateHoldsTheUpdate() {
        // Built from a real index: the hand-listed permission names updateList, never updateOne.
        EndpointIndex real = new EndpointIndex(() -> List.of(
                new io.softa.starter.permission.spi.PermissionEndpointSource.PermissionEndpointDef(
                        "permission.employee.bulkEdit", List.of("POST /Employee/updateList"), null),
                new io.softa.starter.permission.spi.PermissionEndpointSource.PermissionEndpointDef(
                        VIEW, List.of("POST /Employee/searchList"), null)));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(real, "init");
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenAnswer(inv -> pi);
        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor("Employee"))
                .thenReturn(Set.of(ScopeType.ALL, ScopeType.CUSTOM, ScopeType.MANAGED_DEPARTMENTS));
        service = new PermissionServiceImpl(provider, stubCompiler(), null, modelService, applicability,
                () -> real);
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, "permission.employee.bulkEdit"), ScopeType.MANAGED_DEPARTMENTS, null, null),
                role(3, Set.of(VIEW), ScopeType.ALL, null, null)));

        // Only the bulk editor's departments — not, by falling back to every role, everyone.
        assertThat(scope(AccessType.UPDATE).toString()).contains("departmentId", "10");
        assertThat(Filters.isEmpty(scope(AccessType.READ))).isTrue();
    }

    @Test
    @DisplayName("a snapshot without per-role grants is read as the one combined role it was")
    void aSnapshotFromBeforePerRoleGrantsReadsTheUnions() {
        pi.setModelScopeMap(Map.of("Employee", List.of(rule(ScopeType.MANAGED_DEPARTMENTS))));
        pi.setPermissions(Set.of(VIEW));

        assertThat(scope(AccessType.UPDATE).toString()).contains("departmentId", "10");
    }

    @Test
    @DisplayName("each role is bounded by its own company grant, not by the union of them")
    void eachRoleKeepsItsOwnCompanyGrant() {
        try (MockedStatic<ModelManager> models = Mockito.mockStatic(ModelManager.class)) {
            MetaModel meta = mock(MetaModel.class);
            when(meta.isMultiCompany()).thenReturn(true);
            models.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            models.when(() -> ModelManager.getModel("Employee")).thenReturn(meta);
            pi.setRoleGrants(List.of(
                    role(1, Set.of(VIEW), ScopeType.ALL, null, Set.of(100L)),
                    role(2, Set.of(VIEW), ScopeType.MANAGED_DEPARTMENTS, null, Set.of(200L))));

            String read = scope(AccessType.READ).toString();

            // (company 100) OR (company 200 AND my departments) — never "my departments" across both.
            assertThat(read).contains("100", "200", "departmentId");
        }
    }

    @Test
    @DisplayName("a new record outside the creating roles' rows is refused with the user's sentence")
    void aCreateOutsideTheScopeSaysSo() {
        pi.setRoleGrants(List.of(role(1, Set.of(VIEW), ScopeType.MANAGED_DEPARTMENTS, null, null)));
        when(modelService.count(eq("Employee"), any(Filters.class))).thenReturn(0L);

        assertThatThrownBy(() -> checkIds(AccessType.CREATE, List.of(5L)))
                .isInstanceOf(PermissionException.class)
                .hasMessage("The record is outside your data scope.");
    }

    @Test
    @DisplayName("the id count runs with the action's rows and the caller's own range switched off")
    void theIdCountCarriesTheActionsRows() {
        holdTheExampleRoles();
        when(modelService.count(eq("Employee"), any(Filters.class))).thenReturn(1L);

        checkIds(AccessType.UPDATE, List.of(7L));

        ArgumentCaptor<Filters> counted = ArgumentCaptor.forClass(Filters.class);
        verify(modelService).count(eq("Employee"), counted.capture());
        assertThat(counted.getValue().toString()).contains("7", "departmentId");
    }

    @Test
    @DisplayName("a timeline record with only a future slice is judged by that slice")
    void aTimelineRecordWithOnlyAFutureSliceIsInRange() {
        holdTheExampleRoles();
        try (MockedStatic<ModelManager> models = timelineEmployee()) {
            noSliceToday(false);
            when(modelService.searchList(eq("Employee"), any(FlexQuery.class)))
                    .thenReturn(List.of(Map.of("id", 99L)));

            checkIds(AccessType.UPDATE, List.of(99L));
        }
    }

    @Test
    @DisplayName("a timeline record whose future slice is out of range is still refused")
    void aTimelineRecordWhoseFutureSliceIsOutOfRangeIsRefused() {
        holdTheExampleRoles();
        try (MockedStatic<ModelManager> models = timelineEmployee()) {
            noSliceToday(false);
            when(modelService.searchList(eq("Employee"), any(FlexQuery.class))).thenReturn(List.of());

            assertThatThrownBy(() -> checkIds(AccessType.UPDATE, List.of(99L)))
                    .isInstanceOf(PermissionException.class);
        }
    }

    @Test
    @DisplayName("a timeline record out of range today is not let in by its other slices")
    void aTimelineRecordOutOfRangeTodayIsNotLetInByItsHistory() {
        holdTheExampleRoles();
        try (MockedStatic<ModelManager> models = timelineEmployee()) {
            noSliceToday(true);
            when(modelService.searchList(eq("Employee"), any(FlexQuery.class)))
                    .thenReturn(List.of(Map.of("id", 99L)));

            assertThatThrownBy(() -> checkIds(AccessType.UPDATE, List.of(99L)))
                    .isInstanceOf(PermissionException.class);
        }
    }

    private static MockedStatic<ModelManager> timelineEmployee() {
        MockedStatic<ModelManager> models = Mockito.mockStatic(ModelManager.class);
        models.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
        models.when(() -> ModelManager.getModel("Employee")).thenReturn(mock(MetaModel.class));
        models.when(() -> ModelManager.isTimelineModel("Employee")).thenReturn(true);
        return models;
    }

    /**
     * Today's slice is out of range: the count finds nothing in range. {@code existsToday} says whether
     * the record has a slice today at all.
     */
    private void noSliceToday(boolean existsToday) {
        when(modelService.count(eq("Employee"), any(Filters.class))).thenReturn(0L);
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenAnswer(inv -> {
            boolean scoped = inv.getArgument(1).toString().contains("departmentId");
            return !scoped && existsToday ? List.of(99L) : List.of();
        });
    }
}
