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
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The role's department grant: an outer bound on every model that belongs to a department, kept per
 * role exactly like the company grant.
 *
 * <p>The failures worth a test are all silent ones. An inverted unconfigured default would empty every
 * screen of every existing role; a model whose {@code departmentId} points somewhere other than the
 * department model would be filtered on ids of the wrong table and look empty; and unioning the grants
 * across roles first would let one role's rows reach another role's departments.
 */
class DepartmentGrantTest {

    private static final String TICKET = "Ticket";
    private static final String VIEW = "permission.ticket.view";
    private static final Set<Long> GRANTED = Set.of(10L, 11L, 12L);

    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    private static Filters scope(String model, Set<Long> granted) {
        return new PermissionServiceImpl(null, null, null, null, null).departmentScope(model, granted);
    }

    /** A model carrying {@code departmentId} of the given type and target, taking part unless opted out. */
    private void model(String name, FieldType type, String relatedModel, boolean enabled) {
        modelManager.when(() -> ModelManager.existModel(name)).thenReturn(true);
        modelManager.when(() -> ModelManager.isDepartmentGrantEnabled(name)).thenReturn(enabled);
        MetaModel meta = mock(MetaModel.class);
        modelManager.when(() -> ModelManager.getModel(name)).thenReturn(meta);
        if (type != null) {
            MetaField field = mock(MetaField.class);
            when(field.getFieldType()).thenReturn(type);
            when(field.getRelatedModel()).thenReturn(relatedModel);
            modelManager.when(() -> ModelManager.getModelFieldOrNull(name, ModelConstant.DEPARTMENT_FIELD))
                    .thenReturn(field);
        }
    }

    private void anchored(String name) {
        model(name, FieldType.MANY_TO_ONE, ModelConstant.DEPARTMENT_MODEL, true);
    }

    // ---- the three states ------------------------------------------------

    @Test
    @DisplayName("no department grant is no restriction, and asks nothing of the metadata")
    void aMissingGrantMeansUnrestricted() {
        anchored(TICKET);

        assertThat(scope(TICKET, null)).isNull();
        modelManager.verifyNoInteractions();
    }

    @Test
    @DisplayName("a configured, empty grant reaches no department")
    void anEmptyGrantMatchesNothing() {
        anchored(TICKET);

        assertThat(scope(TICKET, Set.of()).toString()).contains("\"IN\",[]");
    }

    @Test
    @DisplayName("a grant bounds departmentId to exactly the granted subtree ids, in a stable order")
    void boundsTheAnchorToTheGrantedIds() {
        anchored(TICKET);

        Filters first = scope(TICKET, new HashSet<>(List.of(12L, 10L, 11L)));
        Filters second = scope(TICKET, new HashSet<>(List.of(11L, 12L, 10L)));

        assertThat(first).isEqualTo(Filters.of(ModelConstant.DEPARTMENT_FIELD, Operator.IN, List.of(10L, 11L, 12L)));
        assertThat(first.toString()).isEqualTo(second.toString());
    }

    // ---- what it bounds and what it leaves -------------------------------

    @Test
    @DisplayName("the department model is bounded by its own id")
    void boundsTheDepartmentListItself() {
        model(ModelConstant.DEPARTMENT_MODEL, null, null, true);

        Filters result = scope(ModelConstant.DEPARTMENT_MODEL, GRANTED);

        assertThat(Filters.containsField(result, ModelConstant.ID)).isTrue();
        assertThat(Filters.containsField(result, ModelConstant.DEPARTMENT_FIELD)).isFalse();
    }

    @Test
    @DisplayName("a model that opted out is left alone")
    void leavesAModelThatOptedOut() {
        model("ApprovalRule", FieldType.MANY_TO_ONE, ModelConstant.DEPARTMENT_MODEL, false);

        assertThat(scope("ApprovalRule", GRANTED)).isNull();
    }

    @Test
    @DisplayName("a departmentId that is a plain number, or points at another model, is not an anchor")
    void leavesADepartmentIdThatIsNotARelationOntoTheDepartmentModel() {
        model("FlowRecord", FieldType.LONG, null, true);
        model("CostCentre", FieldType.MANY_TO_ONE, "OrgUnit", true);

        assertThat(scope("FlowRecord", GRANTED)).isNull();
        assertThat(scope("CostCentre", GRANTED)).isNull();
    }

    @Test
    @DisplayName("a model without departmentId is left alone")
    void leavesAModelWithoutTheAnchor() {
        model("Currency", null, null, true);

        assertThat(scope("Currency", GRANTED)).isNull();
    }

    // ---- per role, never unioned ----------------------------------------

    @Test
    @DisplayName("each role is bounded by its own department grant, then the roles are OR-ed")
    void eachRoleKeepsItsOwnDepartmentGrant() {
        anchored(TICKET);
        PermissionInfo pi = new PermissionInfo();
        // Role 1 reads every ticket of department 10; role 2 reads only its own tickets, in department 20.
        pi.setRoleGrants(List.of(
                role(1, ScopeType.ALL, Set.of(10L)),
                role(2, ScopeType.CREATED_BY_SELF, Set.of(20L))));

        Filters read = read(pi);

        // (department 10) OR (department 20 AND created by me) — never "every ticket of 10 and 20".
        assertThat(read.toString()).contains("[10]", "[20]", "createdId");
        assertThat(read.toString()).doesNotContain("[10,20]");
    }

    @Test
    @DisplayName("a role with no department grant beside one that has it still reaches every department")
    void anUnrestrictedRoleStaysUnrestricted() {
        anchored(TICKET);
        PermissionInfo pi = new PermissionInfo();
        pi.setRoleGrants(List.of(role(1, ScopeType.ALL, Set.of(10L)), role(2, ScopeType.ALL, null)));

        assertThat(Filters.isEmpty(read(pi))).isTrue();
    }

    @Test
    @DisplayName("the department grant and the company grant are both applied, AND-ed")
    void theTwoAxesCombine() {
        anchored(TICKET);
        when(ModelManager.getModel(TICKET).isMultiCompany()).thenReturn(true);
        PermissionInfo pi = new PermissionInfo();
        RoleGrant grant = role(1, ScopeType.ALL, Set.of(10L));
        grant.setGrantedCompanyIds(Set.of(500L));
        pi.setRoleGrants(List.of(grant));

        String read = read(pi).toString();

        assertThat(read).contains(ModelConstant.COMPANY_FIELD, "500", ModelConstant.DEPARTMENT_FIELD, "[10]");
    }

    private static RoleGrant role(long id, ScopeType scope, Set<Long> departments) {
        ScopeRule rule = new ScopeRule();
        rule.setScopeType(scope);
        RoleGrant g = new RoleGrant();
        g.setRoleId(id);
        g.setPermissions(new HashSet<>(Set.of(VIEW)));
        Map<String, List<ScopeRule>> scopes = new HashMap<>();
        scopes.put(TICKET, List.of(rule));
        g.setModelScopeMap(scopes);
        g.setModelScopeConditions(Map.of());
        g.setModelSensitiveFieldSetsMap(Map.of());
        g.setGrantedDepartmentIds(departments);
        return g;
    }

    @SuppressWarnings("unchecked")
    private static Filters read(PermissionInfo pi) {
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenReturn(pi);
        EndpointIndex index = mock(EndpointIndex.class);
        when(index.modelActionPermissions(TICKET, "view")).thenReturn(Set.of(VIEW));
        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor(TICKET))
                .thenReturn(Set.of(ScopeType.ALL, ScopeType.CUSTOM, ScopeType.CREATED_BY_SELF));
        ScopeRuleCompiler compiler = mock(ScopeRuleCompiler.class);
        when(compiler.compile(anyList(), anyString())).thenAnswer(inv -> {
            List<ScopeRule> rules = inv.getArgument(0);
            return rules.stream().anyMatch(r -> r.getScopeType() == ScopeType.ALL)
                    ? null
                    : Filters.of(ModelConstant.CREATED_ID, Operator.EQUAL, 2L);
        });
        PermissionServiceImpl service = new PermissionServiceImpl(provider, compiler, null,
                (ModelService<Long>) mock(ModelService.class), applicability, () -> index);
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);
        return ContextHolder.callWith(ctx, () -> AccessScope.callAs(AccessType.READ,
                () -> service.appendScopeAccessFilters(TICKET, new Filters())));
    }
}
