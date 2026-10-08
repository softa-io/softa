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

/**
 * A sensitive field shows on a row when a role that reads the row grants it, and may be written on a
 * row only by a role that both edits the row and grants it.
 *
 * <p>R1 edits a department without IPA, R2 edits the department's non-Employment-Pass holders with
 * IPA, R3 is the all-staff directory. Employee 1 is a Work Permit holder in the department, employee 2
 * an Employment Pass holder in it.
 */
class GrantPairFieldMaskTest {

    private static final String VIEW = "permission.employee.view";
    private static final String UPDATE = "permission.employee.update";
    private static final String CREATE = "permission.employee.create";
    private static final Set<String> IPA = Set.of("ipaPosition", "ipaBasic");
    private static final String NOT_EP =
            "[[\"employeeProfileId.residenceStatus\",\"!=\",\"SG_EmploymentPass\"]]";

    private PermissionInfo pi;
    private ModelService<Long> modelService;
    private PermissionServiceImpl service;
    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() {
        pi = new PermissionInfo();
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenAnswer(inv -> pi);

        EndpointIndex index = mock(EndpointIndex.class);
        when(index.lookup(anyString(), anyString())).thenReturn(Set.of());
        when(index.lookup("/Employee/searchPage", "POST")).thenReturn(Set.of(VIEW));
        when(index.lookup("/Employee/updateOne", "POST")).thenReturn(Set.of(UPDATE));
        when(index.lookup("/Employee/createOne", "POST")).thenReturn(Set.of(CREATE));

        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor("Employee"))
                .thenReturn(Set.of(ScopeType.ALL, ScopeType.CUSTOM, ScopeType.MANAGED_DEPARTMENTS));

        SensitiveFieldSetCache sets = mock(SensitiveFieldSetCache.class);
        when(sets.hasSensitiveFieldsOn("Employee")).thenReturn(true);
        when(sets.allSensitiveFieldsOn("Employee")).thenReturn(IPA);
        when(sets.grantedFieldsFor(eq("Employee"), anySet())).thenAnswer(inv -> {
            Set<String> granted = inv.getArgument(1);
            return granted.contains("ipa-details") ? IPA : Set.of();
        });
        when(sets.computeForbiddenFields(eq("Employee"), anySet())).thenAnswer(inv -> {
            Set<String> granted = inv.getArgument(1);
            return granted.contains("ipa-details") ? Set.of() : IPA;
        });
        when(sets.setIdsContaining(eq("Employee"), anyString())).thenReturn(Set.of("ipa-details"));
        when(sets.nameOf("ipa-details")).thenReturn("IPA Details");
        when(sets.labelOf("ipa-details")).thenReturn("IPA");
        when(sets.setIdsOwnedBy("Employee")).thenReturn(Set.of("ipa-details"));

        models = Mockito.mockStatic(ModelManager.class);
        MetaModel meta = mock(MetaModel.class);
        when(meta.getLabel()).thenReturn("Employee");
        models.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
        models.when(() -> ModelManager.getModel("Employee")).thenReturn(meta);

        modelService = mockModelService();
        service = new PermissionServiceImpl(provider, stubCompiler(), sets, modelService, applicability,
                () -> index);
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
            ScopeRule r = rules.getFirst();
            return r.getScopeType() == ScopeType.CUSTOM
                    ? Filters.of(r.getScopeExpr().toString())
                    : Filters.of("departmentId", Operator.IN, List.of(10L));
        });
        return compiler;
    }

    private static RoleGrant role(long id, Set<String> permissions, ScopeType scope, String condition, boolean ipa) {
        ScopeRule rule = new ScopeRule();
        rule.setScopeType(scope);
        RoleGrant g = new RoleGrant();
        g.setRoleId(id);
        g.setPermissions(new HashSet<>(permissions));
        g.setModelScopeMap(Map.of("Employee", List.of(rule)));
        Map<String, JsonNode> conditions = new HashMap<>();
        if (condition != null) conditions.put("Employee", JsonUtils.stringToObject(condition, JsonNode.class));
        g.setModelScopeConditions(conditions);
        g.setModelSensitiveFieldSetsMap(ipa ? Map.of("Employee", Set.of("ipa-details")) : Map.of());
        return g;
    }

    private void holdTheExampleRoles() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, null, false),
                role(2, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, NOT_EP, true),
                role(3, Set.of(VIEW), ScopeType.ALL, null, false)));
    }

    private static Map<String, Object> row(long id, Object ipaBasic) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("name", "E" + id);
        row.put("ipaBasic", ipaBasic);
        return row;
    }

    private <T> T as(java.util.function.Supplier<T> call) {
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);
        return ContextHolder.callWith(ctx, call::get);
    }

    @Test
    @DisplayName("in one list, the Work Permit holder shows IPA and the Employment Pass holder does not")
    void theSameListShowsIpaOnlyOnTheRowsOfTheRoleGrantingIt() {
        holdTheExampleRoles();
        // R2's rows (department AND not EP) hold employee 1 only.
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenReturn(List.of(1L));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(1, 1800), row(2, 6000)));

        as(() -> service.maskResponseValue("Employee", rows, AccessType.READ));

        assertThat(rows.get(0).get("ipaBasic")).isEqualTo(1800);
        assertThat(rows.get(1).get("ipaBasic")).isNull();
        assertThat(rows.get(1).get("name")).isEqualTo("E2");
    }

    @Test
    @DisplayName("a field some reading role grants stays in the SELECT; it is masked per row afterwards")
    void aConditionalFieldIsStillSelected() {
        holdTheExampleRoles();

        assertThat(as(() -> service.filterReadableFields("Employee", List.of("id", "ipaBasic"), AccessType.READ)))
                .containsExactly("id", "ipaBasic");
    }

    @Test
    @DisplayName("a user with no IPA role has the field dropped and masked without any per-row query")
    void withoutAnyGrantingRoleTheFieldIsHiddenEverywhere() {
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW), ScopeType.ALL, null, false)));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(1, 1800)));

        assertThat(as(() -> service.filterReadableFields("Employee", List.of("id", "ipaBasic"), AccessType.READ)))
                .containsExactly("id");
        as(() -> service.maskResponseValue("Employee", rows, AccessType.READ));

        assertThat(rows.getFirst().get("ipaBasic")).isNull();
        verify(modelService, never()).getIds(anyString(), any(Filters.class));
    }

    @Test
    @DisplayName("a row without its id shows only what every reading role grants")
    void aRowWithoutAnIdIsMaskedConservatively() {
        holdTheExampleRoles();
        Map<String, Object> row = new HashMap<>();
        row.put("ipaBasic", 1800);

        as(() -> service.maskResponseValue("Employee", List.of(row), AccessType.READ));

        assertThat(row.get("ipaBasic")).isNull();
    }

    @Test
    @DisplayName("writing IPA on the Employment Pass holder is refused with the user's sentence")
    void writingAFieldOutsideTheGrantingEditorsRowsIsRefused() {
        holdTheExampleRoles();
        // R1 may edit employee 2 (the row check passes); R2, the one granting IPA, does not reach it.
        when(modelService.count(eq("Employee"), any(Filters.class))).thenReturn(1L, 0L);

        assertThatThrownBy(() -> as(() -> {
            service.checkIdsFieldsAccess("Employee", List.of(2L), Set.of("ipaBasic"), AccessType.UPDATE);
            return null;
        })).isInstanceOf(PermissionException.class)
                .hasMessage("You don't have permission to edit IPA fields for this employee.");
    }

    @Test
    @DisplayName("writing IPA is checked against the rows of the roles that both edit and grant it")
    void theWriteCheckCountsTheGrantingEditorsRows() {
        holdTheExampleRoles();
        when(modelService.count(eq("Employee"), any(Filters.class))).thenReturn(1L);

        as(() -> {
            service.checkIdsFieldsAccess("Employee", List.of(1L), Set.of("ipaBasic"), AccessType.UPDATE);
            return null;
        });

        ArgumentCaptor<Filters> counted = ArgumentCaptor.forClass(Filters.class);
        verify(modelService, Mockito.times(2)).count(eq("Employee"), counted.capture());
        // The second count is the field's: R2 only — the condition is what tells it apart from R1.
        assertThat(counted.getAllValues().get(1).toString()).contains("residenceStatus");
    }

    @Test
    @DisplayName("a role that views with IPA but cannot edit does not lend IPA to an editing role")
    void viewingWithIpaPlusEditingWithoutItCannotWriteIpa() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, null, false),
                role(4, Set.of(VIEW), ScopeType.ALL, null, true)));

        assertThatThrownBy(() -> as(() -> {
            service.checkIdsFieldsAccess("Employee", List.of(1L), Set.of("ipaBasic"), AccessType.UPDATE);
            return null;
        })).isInstanceOf(PermissionException.class);
    }

    @Test
    @DisplayName("a condition on IPA matches only on the rows where the caller may see IPA")
    void aConditionOnASensitiveFieldIsBoundToTheGrantingRolesRows() {
        holdTheExampleRoles();

        Filters scoped = as(() -> service.appendScopeAccessFilters("Employee",
                Filters.of("ipaBasic", Operator.GREATER_THAN, 5000)));

        assertThat(scoped.toString()).contains("ipaBasic", "residenceStatus");
    }

    @Test
    @DisplayName("a condition on a field no role grants matches nothing")
    void aConditionOnAnUngrantedFieldMatchesNothing() {
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW), ScopeType.ALL, null, false)));

        Filters scoped = as(() -> service.appendScopeAccessFilters("Employee",
                Filters.of("ipaBasic", Operator.GREATER_THAN, 5000)));

        assertThat(scoped).isEqualTo(ScopeRuleCompiler.matchNone());
    }

    @Test
    @DisplayName("rows read elsewhere — a change log's values — are masked the same way")
    void rowsFromElsewhereAreMaskedByTheirId() {
        holdTheExampleRoles();
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenReturn(List.of(1L));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(1, 1800), row(2, 6000)));

        as(() -> {
            service.maskRows("Employee", rows);
            return null;
        });

        assertThat(rows.get(0).get("ipaBasic")).isEqualTo(1800);
        assertThat(rows.get(1).get("ipaBasic")).isNull();
    }

    /** R1's department holds both employees; R2's non-EP part of it only employee 1. */
    private void departmentHoldsBothAndNonEpHoldsOne() {
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenAnswer(inv -> {
            Filters f = inv.getArgument(1);
            return f.toString().contains("residenceStatus") ? List.of(1L) : List.of(1L, 2L);
        });
    }

    private void unionOf(String... permissions) {
        pi.setPermissions(Set.of(permissions));
    }

    @Test
    @DisplayName("record access: the Work Permit holder's IPA is editable, the Employment Pass holder's hidden")
    void recordAccessFollowsTheRolesReachingEachRecord() {
        holdTheExampleRoles();
        unionOf(VIEW, UPDATE);
        departmentHoldsBothAndNonEpHoldsOne();

        List<io.softa.framework.orm.domain.RecordAccess> access =
                as(() -> service.getRecordAccess("Employee", List.of(1L, 2L)));

        assertThat(access.get(0).hiddenSets()).isEmpty();
        assertThat(access.get(0).readonlySets()).isEmpty();
        assertThat(access.get(0).actions()).contains(AccessType.UPDATE);
        assertThat(access.get(1).hiddenSets()).containsExactly("ipa-details");
        assertThat(access.get(1).actions()).contains(AccessType.UPDATE);
    }

    @Test
    @DisplayName("record access: a set granted only by a role that views is read-only")
    void aSetGrantedOnlyByAViewingRoleIsReadOnly() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, null, false),
                role(4, Set.of(VIEW), ScopeType.ALL, null, true)));
        unionOf(VIEW, UPDATE);
        departmentHoldsBothAndNonEpHoldsOne();

        List<io.softa.framework.orm.domain.RecordAccess> access =
                as(() -> service.getRecordAccess("Employee", List.of(1L)));

        assertThat(access.getFirst().hiddenSets()).isEmpty();
        assertThat(access.getFirst().readonlySets()).containsExactly("ipa-details");
    }

    @Test
    @DisplayName("record access: without the update action anywhere, nothing is editable and no Edit is offered")
    void withoutTheUpdateActionEverythingVisibleIsReadOnly() {
        pi.setRoleGrants(List.of(role(4, Set.of(VIEW), ScopeType.ALL, null, true)));
        unionOf(VIEW);

        List<io.softa.framework.orm.domain.RecordAccess> access =
                as(() -> service.getRecordAccess("Employee", List.of(1L)));

        assertThat(access.getFirst().readonlySets()).containsExactly("ipa-details");
        assertThat(access.getFirst().actions()).doesNotContain(AccessType.UPDATE);
    }

    @Test
    @DisplayName("create access: the set shows on the create form only if a creating role grants it")
    void theCreateFormShowsWhatACreatingRoleGrants() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, CREATE), ScopeType.MANAGED_DEPARTMENTS, null, false),
                role(4, Set.of(VIEW), ScopeType.ALL, null, true)));
        assertThat(as(() -> service.getCreateAccess("Employee")).hiddenSets()).containsExactly("ipa-details");

        pi.setRoleGrants(List.of(role(2, Set.of(VIEW, CREATE), ScopeType.MANAGED_DEPARTMENTS, NOT_EP, true)));
        assertThat(as(() -> service.getCreateAccess("Employee")).hiddenSets()).isEmpty();
    }

    @Test
    @DisplayName("an import row is refused in the uploader's words")
    void anImportRowSaysUpdate() {
        holdTheExampleRoles();
        when(modelService.count(eq("Employee"), any(Filters.class))).thenReturn(1L, 0L);

        assertThatThrownBy(() -> as(() -> {
            io.softa.framework.orm.service.ImportScope.run(() -> service.checkIdsFieldsAccess(
                    "Employee", List.of(2L), Set.of("ipaBasic"), AccessType.UPDATE));
            return null;
        })).isInstanceOf(PermissionException.class)
                .hasMessage("You don't have permission to update IPA fields for this employee.");
    }

    @Test
    @DisplayName("a sort on IPA is dropped when IPA is hidden on some rows; other sorts stay")
    void aSortOnAPartlyHiddenFieldIsDropped() {
        holdTheExampleRoles();
        io.softa.framework.orm.domain.FlexQuery query = new io.softa.framework.orm.domain.FlexQuery();
        query.setOrders(io.softa.framework.orm.domain.Orders.ofDesc("ipaBasic").addAsc("name"));

        as(() -> {
            service.guardQuery("Employee", query);
            return null;
        });

        assertThat(query.getOrders().getFields()).containsExactly("name");
    }

    @Test
    @DisplayName("grouping by IPA is refused when IPA is hidden on some rows")
    void groupingByAPartlyHiddenFieldIsRefused() {
        holdTheExampleRoles();
        io.softa.framework.orm.domain.FlexQuery query = new io.softa.framework.orm.domain.FlexQuery();
        query.setGroupBy(List.of("ipaBasic"));

        assertThatThrownBy(() -> as(() -> {
            service.guardQuery("Employee", query);
            return null;
        })).isInstanceOf(PermissionException.class);
    }

    @Test
    @DisplayName("a user who sees IPA on every row keeps the sort")
    void aUniformlyVisibleFieldKeepsItsSort() {
        pi.setRoleGrants(List.of(role(4, Set.of(VIEW), ScopeType.ALL, null, true)));
        io.softa.framework.orm.domain.FlexQuery query = new io.softa.framework.orm.domain.FlexQuery();
        query.setOrders(io.softa.framework.orm.domain.Orders.ofDesc("ipaBasic"));

        as(() -> {
            service.guardQuery("Employee", query);
            return null;
        });

        assertThat(query.getOrders().getFields()).containsExactly("ipaBasic");
    }

    @Test
    @DisplayName("record access: a child's set shown on the form follows the record that owns the child")
    void anAttachedChildSetFollowsTheOwningRecord() {
        // Banking lives on EmpBankAccount and is shown on the employee form; R1 grants it.
        RoleGrant banking = role(1, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, null, false);
        banking.setModelSensitiveFieldSetsMap(Map.of("EmpBankAccount", Set.of("employee-bank")));
        pi.setRoleGrants(List.of(banking, role(3, Set.of(VIEW), ScopeType.ALL, null, false)));
        unionOf(VIEW, UPDATE);
        departmentHoldsBothAndNonEpHoldsOne();
        SensitiveFieldSetCache sets = (SensitiveFieldSetCache) org.springframework.test.util.ReflectionTestUtils
                .getField(service, "sfsCache");
        when(sets.setIdsAttachedTo("Employee")).thenReturn(Set.of("employee-bank"));
        when(sets.modelOf("employee-bank")).thenReturn("EmpBankAccount");
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenAnswer(inv ->
                inv.getArgument(1).toString().contains("departmentId") ? List.of(1L) : List.of(1L, 2L));

        List<io.softa.framework.orm.domain.RecordAccess> access =
                as(() -> service.getRecordAccess("Employee", List.of(1L, 2L)));

        assertThat(access.get(0).hiddenSets()).doesNotContain("employee-bank");
        assertThat(access.get(1).hiddenSets()).contains("employee-bank");
    }
}
