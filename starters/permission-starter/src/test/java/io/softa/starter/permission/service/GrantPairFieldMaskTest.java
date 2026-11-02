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
 * <p>R1 edits a department without pay, R2 edits the department's permanent staff with
 * pay, R3 is the all-staff directory. Employee 1 is a permanent employee in the department, employee 2
 * a contractor in it.
 */
class GrantPairFieldMaskTest {

    private static final String VIEW = "permission.employee.view";
    private static final String UPDATE = "permission.employee.update";
    private static final String CREATE = "permission.employee.create";
    private static final Set<String> PAY = Set.of("payGrade", "basePay");
    private static final String NOT_CONTRACTOR =
            "[[\"employmentType\",\"!=\",\"Contractor\"]]";

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
        when(index.modelActionPermissions("Employee", "view")).thenReturn(Set.of(VIEW));
        when(index.modelActionPermissions("Employee", "update")).thenReturn(Set.of(UPDATE));
        when(index.modelActionPermissions("Employee", "create")).thenReturn(Set.of(CREATE));

        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor("Employee"))
                .thenReturn(Set.of(ScopeType.ALL, ScopeType.CUSTOM, ScopeType.MANAGED_DEPARTMENTS));

        SensitiveFieldSetCache sets = mock(SensitiveFieldSetCache.class);
        when(sets.hasSensitiveFieldsOn("Employee")).thenReturn(true);
        when(sets.allSensitiveFieldsOn("Employee")).thenReturn(PAY);
        when(sets.grantedFieldsFor(eq("Employee"), anySet())).thenAnswer(inv -> {
            Set<String> granted = inv.getArgument(1);
            return granted.contains("pay-details") ? PAY : Set.of();
        });
        when(sets.computeForbiddenFields(eq("Employee"), anySet())).thenAnswer(inv -> {
            Set<String> granted = inv.getArgument(1);
            return granted.contains("pay-details") ? Set.of() : PAY;
        });
        when(sets.setIdsContaining(eq("Employee"), anyString())).thenReturn(Set.of("pay-details"));
        when(sets.nameOf("pay-details")).thenReturn("Pay Details");
        when(sets.shortNameOf("pay-details")).thenReturn("Pay");
        when(sets.setIdsOwnedBy("Employee")).thenReturn(Set.of("pay-details"));
        when(sets.modelOf(anyString())).thenReturn("Employee");
        when(sets.fieldCodesOf("pay-details")).thenReturn(PAY);

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

    private static RoleGrant role(long id, Set<String> permissions, ScopeType scope, String condition, boolean pay) {
        ScopeRule rule = new ScopeRule();
        rule.setScopeType(scope);
        RoleGrant g = new RoleGrant();
        g.setRoleId(id);
        g.setPermissions(new HashSet<>(permissions));
        g.setModelScopeMap(Map.of("Employee", List.of(rule)));
        Map<String, JsonNode> conditions = new HashMap<>();
        if (condition != null) conditions.put("Employee", JsonUtils.stringToObject(condition, JsonNode.class));
        g.setModelScopeConditions(conditions);
        g.setModelSensitiveFieldSetsMap(pay ? Map.of("Employee", Set.of("pay-details")) : Map.of());
        return g;
    }

    private void holdTheExampleRoles() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, null, false),
                role(2, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, NOT_CONTRACTOR, true),
                role(3, Set.of(VIEW), ScopeType.ALL, null, false)));
    }

    private static Map<String, Object> row(long id, Object basePay) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("name", "E" + id);
        row.put("basePay", basePay);
        return row;
    }

    private <T> T as(java.util.function.Supplier<T> call) {
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);
        return ContextHolder.callWith(ctx, call::get);
    }

    @Test
    @DisplayName("in one list, the permanent employee shows pay and the contractor does not")
    void theSameListShowsPayOnlyOnTheRowsOfTheRoleGrantingIt() {
        holdTheExampleRoles();
        // R2's rows (department AND not contractor) hold employee 1 only.
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenReturn(List.of(1L));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(1, 1800), row(2, 6000)));

        as(() -> service.maskResponseValue("Employee", rows, AccessType.READ));

        assertThat(rows.get(0).get("basePay")).isEqualTo(1800);
        assertThat(rows.get(1).get("basePay")).isNull();
        assertThat(rows.get(1).get("name")).isEqualTo("E2");
    }

    @Test
    @DisplayName("a field some reading role grants stays in the SELECT; it is masked per row afterwards")
    void aPerRowFieldIsStillSelected() {
        holdTheExampleRoles();

        assertThat(as(() -> service.filterReadableFields("Employee", List.of("id", "basePay"), AccessType.READ)))
                .containsExactly("id", "basePay");
    }

    @Test
    @DisplayName("a user with no pay role has the field dropped and masked without any per-row query")
    void withoutAnyGrantingRoleTheFieldIsHiddenEverywhere() {
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW), ScopeType.ALL, null, false)));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row(1, 1800)));

        assertThat(as(() -> service.filterReadableFields("Employee", List.of("id", "basePay"), AccessType.READ)))
                .containsExactly("id");
        as(() -> service.maskResponseValue("Employee", rows, AccessType.READ));

        assertThat(rows.getFirst().get("basePay")).isNull();
        verify(modelService, never()).getIds(anyString(), any(Filters.class));
    }

    @Test
    @DisplayName("a row without its id shows only what every reading role grants")
    void aRowWithoutAnIdIsMaskedConservatively() {
        holdTheExampleRoles();
        Map<String, Object> row = new HashMap<>();
        row.put("basePay", 1800);

        as(() -> service.maskResponseValue("Employee", List.of(row), AccessType.READ));

        assertThat(row.get("basePay")).isNull();
    }

    @Test
    @DisplayName("writing pay on the contractor is refused with the user's sentence")
    void writingAFieldOutsideTheGrantingEditorsRowsIsRefused() {
        holdTheExampleRoles();
        // R1 may edit employee 2 (the row check passes); R2, the one granting pay, does not reach it.
        when(modelService.count(eq("Employee"), any(Filters.class))).thenReturn(1L, 0L);

        assertThatThrownBy(() -> as(() -> {
            service.checkIdsFieldsAccess("Employee", List.of(2L), Set.of("basePay"), AccessType.UPDATE);
            return null;
        })).isInstanceOf(PermissionException.class)
                .hasMessage("You don't have permission to edit Pay fields for this employee.")
                // The sentence is the user's, so it rides on the field for a client to show.
                .extracting(e -> ((PermissionException) e).getFieldErrors())
                .isEqualTo(Map.of("basePay", "You don't have permission to edit Pay fields for this employee."));
    }

    @Test
    @DisplayName("writing pay is checked against the rows of the roles that both edit and grant it")
    void theWriteCheckCountsTheGrantingEditorsRows() {
        holdTheExampleRoles();
        when(modelService.count(eq("Employee"), any(Filters.class))).thenReturn(1L);

        as(() -> {
            service.checkIdsFieldsAccess("Employee", List.of(1L), Set.of("basePay"), AccessType.UPDATE);
            return null;
        });

        ArgumentCaptor<Filters> counted = ArgumentCaptor.forClass(Filters.class);
        verify(modelService, Mockito.times(2)).count(eq("Employee"), counted.capture());
        // The second count is the field's: R2 only — the condition is what tells it apart from R1.
        assertThat(counted.getAllValues().get(1).toString()).contains("employmentType");
    }

    @Test
    @DisplayName("a role that views with pay but cannot edit does not lend pay to an editing role")
    void viewingWithPayPlusEditingWithoutItCannotWritePay() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, null, false),
                role(4, Set.of(VIEW), ScopeType.ALL, null, true)));

        assertThatThrownBy(() -> as(() -> {
            service.checkIdsFieldsAccess("Employee", List.of(1L), Set.of("basePay"), AccessType.UPDATE);
            return null;
        })).isInstanceOf(PermissionException.class);
    }

    @Test
    @DisplayName("a condition on pay matches only on the rows where the caller may see pay")
    void aConditionOnASensitiveFieldIsBoundToTheGrantingRolesRows() {
        holdTheExampleRoles();

        Filters scoped = as(() -> service.appendScopeAccessFilters("Employee",
                Filters.of("basePay", Operator.GREATER_THAN, 5000)));

        assertThat(scoped.toString()).contains("basePay", "employmentType");
    }

    @Test
    @DisplayName("a condition on a field no role grants matches nothing")
    void aConditionOnAnUngrantedFieldMatchesNothing() {
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW), ScopeType.ALL, null, false)));

        Filters scoped = as(() -> service.appendScopeAccessFilters("Employee",
                Filters.of("basePay", Operator.GREATER_THAN, 5000)));

        assertThat(scoped).isEqualTo(ScopeRuleCompiler.matchNone());
    }

    @Test
    @DisplayName("a label lookup ignoring the row range still matches a condition on an ungranted field nowhere")
    void ignoringTheRowRangeAConditionOnAnUngrantedFieldStillMatchesNothing() {
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW), ScopeType.ALL, null, false)));

        Filters guarded = as(() -> service.appendScopeAccessFiltersIgnoringRowScope("Employee",
                Filters.of("basePay", Operator.GREATER_THAN, 5000)));

        assertThat(guarded).isEqualTo(ScopeRuleCompiler.matchNone());
    }

    @Test
    @DisplayName("a label lookup ignoring the row range binds a condition on pay to the granting role's rows only")
    void ignoringTheRowRangeAConditionOnAPerRowFieldIsBoundToTheGrantingRole() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, null, false),
                role(2, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, NOT_CONTRACTOR, true)));
        Filters condition = Filters.of("basePay", Operator.GREATER_THAN, 5000);

        Filters guarded = as(() -> service.appendScopeAccessFiltersIgnoringRowScope("Employee", condition));

        // The condition AND R2's rows — and nothing of the caller's row range, which would add R1's too.
        assertThat(guarded.toString()).contains("basePay", "employmentType");
        assertThat(guarded.toString().split("departmentId", -1)).hasSize(2);
        Filters scoped = as(() -> service.appendScopeAccessFilters("Employee", condition));
        assertThat(scoped.toString().split("departmentId", -1)).hasSizeGreaterThan(2);
    }

    @Test
    @DisplayName("a label lookup ignoring the row range leaves a condition on an ordinary field alone")
    void ignoringTheRowRangeAnOrdinaryConditionIsUntouched() {
        holdTheExampleRoles();
        Filters condition = Filters.of("name", Operator.EQUAL, "E1");

        assertThat(as(() -> service.appendScopeAccessFiltersIgnoringRowScope("Employee", condition)))
                .isSameAs(condition);
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

        assertThat(rows.get(0).get("basePay")).isEqualTo(1800);
        assertThat(rows.get(1).get("basePay")).isNull();
    }

    /** R1's department holds both employees; R2's non-contractor part of it only employee 1. */
    private void departmentHoldsBothAndNonContractorsHoldOne() {
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenAnswer(inv -> {
            Filters f = inv.getArgument(1);
            return f.toString().contains("employmentType") ? List.of(1L) : List.of(1L, 2L);
        });
    }

    private void unionOf(String... permissions) {
        pi.setPermissions(Set.of(permissions));
    }

    @Test
    @DisplayName("record access: the permanent employee's pay is editable, the contractor's hidden")
    void recordAccessFollowsTheRolesReachingEachRecord() {
        holdTheExampleRoles();
        unionOf(VIEW, UPDATE);
        departmentHoldsBothAndNonContractorsHoldOne();

        List<io.softa.framework.orm.domain.RecordAccess> access =
                as(() -> service.getRecordAccess("Employee", List.of(1L, 2L)));

        assertThat(access.get(0).hiddenSets()).isEmpty();
        assertThat(access.get(0).readonlySets()).isEmpty();
        assertThat(access.get(0).actions()).contains(AccessType.UPDATE);
        assertThat(access.get(1).hiddenSets()).containsExactly("pay-details");
        assertThat(access.get(1).actions()).contains(AccessType.UPDATE);
        // The same answer by field, so a page can lock a cell or a form field without the set catalogue.
        assertThat(access.get(0).hiddenFields()).isEmpty();
        assertThat(access.get(1).hiddenFields()).containsOnlyKeys("Employee");
        assertThat(access.get(1).hiddenFields().get("Employee")).containsExactlyInAnyOrder("payGrade", "basePay");
        assertThat(access.get(1).readonlyFields()).isEmpty();
    }

    @Test
    @DisplayName("record access: a set granted only by a role that views is read-only")
    void aSetGrantedOnlyByAViewingRoleIsReadOnly() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, null, false),
                role(4, Set.of(VIEW), ScopeType.ALL, null, true)));
        unionOf(VIEW, UPDATE);
        departmentHoldsBothAndNonContractorsHoldOne();

        List<io.softa.framework.orm.domain.RecordAccess> access =
                as(() -> service.getRecordAccess("Employee", List.of(1L)));

        assertThat(access.getFirst().hiddenSets()).isEmpty();
        assertThat(access.getFirst().readonlySets()).containsExactly("pay-details");
        assertThat(access.getFirst().readonlyFields().get("Employee")).containsExactlyInAnyOrder("payGrade", "basePay");
        assertThat(access.getFirst().hiddenFields()).isEmpty();
    }

    @Test
    @DisplayName("record access: without the update action anywhere, nothing is editable and no Edit is offered")
    void withoutTheUpdateActionEverythingVisibleIsReadOnly() {
        pi.setRoleGrants(List.of(role(4, Set.of(VIEW), ScopeType.ALL, null, true)));
        unionOf(VIEW);

        List<io.softa.framework.orm.domain.RecordAccess> access =
                as(() -> service.getRecordAccess("Employee", List.of(1L)));

        assertThat(access.getFirst().readonlySets()).containsExactly("pay-details");
        assertThat(access.getFirst().actions()).doesNotContain(AccessType.UPDATE);
    }

    @Test
    @DisplayName("record access: a set sharing a field with a granted set is not hidden, as the field shows")
    void aSetSharingAVisibleFieldIsNotHidden() {
        // "grade-info" covers payGrade only, which "pay-details" covers too. A role granting pay-details
        // sees payGrade in the read, so the form must not hide the grade section around it.
        SensitiveFieldSetCache sets = (SensitiveFieldSetCache) org.springframework.test.util.ReflectionTestUtils
                .getField(service, "sfsCache");
        when(sets.setIdsOwnedBy("Employee")).thenReturn(Set.of("pay-details", "grade-info", "bank-info"));
        when(sets.fieldCodesOf("grade-info")).thenReturn(Set.of("payGrade"));
        when(sets.fieldCodesOf("bank-info")).thenReturn(Set.of("bankAccount"));
        when(sets.grantedFieldsFor(eq("Employee"), anySet())).thenAnswer(inv -> {
            Set<String> granted = inv.getArgument(1);
            Set<String> fields = new HashSet<>();
            if (granted.contains("pay-details")) fields.addAll(PAY);
            if (granted.contains("grade-info")) fields.add("payGrade");
            if (granted.contains("bank-info")) fields.add("bankAccount");
            return fields;
        });
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, UPDATE), ScopeType.ALL, null, false),
                role(4, Set.of(VIEW, CREATE), ScopeType.ALL, null, true)));
        unionOf(VIEW, UPDATE);

        List<io.softa.framework.orm.domain.RecordAccess> access =
                as(() -> service.getRecordAccess("Employee", List.of(1L)));

        assertThat(access.getFirst().hiddenSets()).containsExactly("bank-info");
        // Visible through the viewing role, editable through none.
        assertThat(access.getFirst().readonlySets()).containsExactly("grade-info", "pay-details");
        assertThat(access.getFirst().hiddenFields()).isEqualTo(Map.of("Employee", Set.of("bankAccount")));
        assertThat(access.getFirst().readonlyFields().get("Employee")).containsExactlyInAnyOrder("payGrade", "basePay");
        var create = as(() -> service.getCreateAccess("Employee"));
        assertThat(create.hiddenSets()).containsExactly("bank-info");
        assertThat(create.hiddenFields()).isEqualTo(Map.of("Employee", Set.of("bankAccount")));
    }

    @Test
    @DisplayName("record access: the field breakdown follows each field, not the set around it")
    void aSharedFieldFollowsTheSetThatLetsItThrough() {
        // "grade-info" covers payGrade only and is granted by the editing role; "pay-details" only by a
        // viewing one. payGrade is editable through grade-info, so only basePay is read-only.
        SensitiveFieldSetCache sets = (SensitiveFieldSetCache) org.springframework.test.util.ReflectionTestUtils
                .getField(service, "sfsCache");
        when(sets.setIdsOwnedBy("Employee")).thenReturn(Set.of("pay-details", "grade-info"));
        when(sets.fieldCodesOf("grade-info")).thenReturn(Set.of("payGrade"));
        when(sets.grantedFieldsFor(eq("Employee"), anySet())).thenAnswer(inv -> {
            Set<String> granted = inv.getArgument(1);
            Set<String> fields = new HashSet<>();
            if (granted.contains("pay-details")) fields.addAll(PAY);
            if (granted.contains("grade-info")) fields.add("payGrade");
            return fields;
        });
        RoleGrant editor = role(1, Set.of(VIEW, UPDATE), ScopeType.ALL, null, false);
        editor.setModelSensitiveFieldSetsMap(Map.of("Employee", Set.of("grade-info")));
        pi.setRoleGrants(List.of(editor, role(4, Set.of(VIEW), ScopeType.ALL, null, true)));
        unionOf(VIEW, UPDATE);

        List<io.softa.framework.orm.domain.RecordAccess> access =
                as(() -> service.getRecordAccess("Employee", List.of(1L)));

        // Set by set, pay-details counts as editable: one of its fields is. Field by field it is not
        // all editable, and the write check refuses basePay — the breakdown says so.
        assertThat(access.getFirst().readonlySets()).isEmpty();
        assertThat(access.getFirst().readonlyFields()).isEqualTo(Map.of("Employee", Set.of("basePay")));
    }

    @Test
    @DisplayName("create access: the set shows on the create form only if a creating role grants it")
    void theCreateFormShowsWhatACreatingRoleGrants() {
        pi.setRoleGrants(List.of(
                role(1, Set.of(VIEW, CREATE), ScopeType.MANAGED_DEPARTMENTS, null, false),
                role(4, Set.of(VIEW), ScopeType.ALL, null, true)));
        assertThat(as(() -> service.getCreateAccess("Employee")).hiddenSets()).containsExactly("pay-details");

        pi.setRoleGrants(List.of(role(2, Set.of(VIEW, CREATE), ScopeType.MANAGED_DEPARTMENTS, NOT_CONTRACTOR, true)));
        assertThat(as(() -> service.getCreateAccess("Employee")).hiddenSets()).isEmpty();
    }

    @Test
    @DisplayName("an import row is refused in the uploader's words")
    void anImportRowSaysUpdate() {
        holdTheExampleRoles();
        when(modelService.count(eq("Employee"), any(Filters.class))).thenReturn(1L, 0L);

        assertThatThrownBy(() -> as(() -> {
            io.softa.framework.orm.service.ImportScope.run(() -> service.checkIdsFieldsAccess(
                    "Employee", List.of(2L), Set.of("basePay"), AccessType.UPDATE));
            return null;
        })).isInstanceOf(PermissionException.class)
                .hasMessage("You don't have permission to update Pay fields for this employee.");
    }

    @Test
    @DisplayName("the refusal names the model mid-sentence and keeps an acronym's capitals")
    void theRefusalKeepsAnAcronymInTheModelLabel() {
        holdTheExampleRoles();
        MetaModel meta = mock(MetaModel.class);
        when(meta.getLabel()).thenReturn("HR Employee Record");
        models.when(() -> ModelManager.getModel("Employee")).thenReturn(meta);
        when(modelService.count(eq("Employee"), any(Filters.class))).thenReturn(1L, 0L);

        assertThatThrownBy(() -> as(() -> {
            service.checkIdsFieldsAccess("Employee", List.of(2L), Set.of("basePay"), AccessType.UPDATE);
            return null;
        })).isInstanceOf(PermissionException.class)
                .hasMessage("You don't have permission to edit Pay fields for this HR employee record.");
    }

    @Test
    @DisplayName("a label reads mid-sentence: words lowered, acronyms kept")
    void aLabelReadsMidSentence() {
        assertThat(PermissionServiceImpl.inSentence("Employee")).isEqualTo("employee");
        assertThat(PermissionServiceImpl.inSentence("Leave Request")).isEqualTo("leave request");
        assertThat(PermissionServiceImpl.inSentence("HR Case")).isEqualTo("HR case");
        assertThat(PermissionServiceImpl.inSentence("A")).isEqualTo("a");
    }

    @Test
    @DisplayName("a sort on pay is dropped when pay is hidden on some rows; other sorts stay")
    void aSortOnAPartlyHiddenFieldIsDropped() {
        holdTheExampleRoles();
        io.softa.framework.orm.domain.FlexQuery query = new io.softa.framework.orm.domain.FlexQuery();
        query.setOrders(io.softa.framework.orm.domain.Orders.ofDesc("basePay").addAsc("name"));

        as(() -> {
            service.guardQuery("Employee", query);
            return null;
        });

        assertThat(query.getOrders().getFields()).containsExactly("name");
    }

    @Test
    @DisplayName("grouping by pay is refused when pay is hidden on some rows")
    void groupingByAPartlyHiddenFieldIsRefused() {
        holdTheExampleRoles();
        io.softa.framework.orm.domain.FlexQuery query = new io.softa.framework.orm.domain.FlexQuery();
        query.setGroupBy(List.of("basePay"));

        assertThatThrownBy(() -> as(() -> {
            service.guardQuery("Employee", query);
            return null;
        })).isInstanceOf(PermissionException.class);
    }

    @Test
    @DisplayName("a user who sees pay on every row keeps the sort")
    void aUniformlyVisibleFieldKeepsItsSort() {
        pi.setRoleGrants(List.of(role(4, Set.of(VIEW), ScopeType.ALL, null, true)));
        io.softa.framework.orm.domain.FlexQuery query = new io.softa.framework.orm.domain.FlexQuery();
        query.setOrders(io.softa.framework.orm.domain.Orders.ofDesc("basePay"));

        as(() -> {
            service.guardQuery("Employee", query);
            return null;
        });

        assertThat(query.getOrders().getFields()).containsExactly("basePay");
    }

    @Test
    @DisplayName("record access: a child's set shown on the form follows the record that owns the child")
    void anAttachedChildSetFollowsTheOwningRecord() {
        // Banking lives on EmpBankAccount and is shown on the employee form; R1 grants it.
        RoleGrant banking = role(1, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, null, false);
        banking.setModelSensitiveFieldSetsMap(Map.of("EmpBankAccount", Set.of("employee-bank")));
        pi.setRoleGrants(List.of(banking, role(3, Set.of(VIEW), ScopeType.ALL, null, false)));
        unionOf(VIEW, UPDATE);
        departmentHoldsBothAndNonContractorsHoldOne();
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

    // ─── paths into another model's sensitive field ───

    /** EmpLeave.employeeId → Employee, and EmpLeaveDay.leaveId → EmpLeave: one and two to-one hops. */
    private void relationsLeadToEmployee() {
        relation("EmpLeave", "employeeId", io.softa.framework.orm.enums.FieldType.MANY_TO_ONE, "Employee");
        relation("EmpLeaveDay", "leaveId", io.softa.framework.orm.enums.FieldType.MANY_TO_ONE, "EmpLeave");
        relation("EmpLeave", "approverIds", io.softa.framework.orm.enums.FieldType.ONE_TO_MANY, "Employee");
    }

    private void relation(String model, String field, io.softa.framework.orm.enums.FieldType type, String related) {
        io.softa.framework.orm.meta.MetaField f = new io.softa.framework.orm.meta.MetaField();
        org.springframework.test.util.ReflectionTestUtils.setField(f, "fieldName", field);
        org.springframework.test.util.ReflectionTestUtils.setField(f, "fieldType", type);
        org.springframework.test.util.ReflectionTestUtils.setField(f, "relatedModel", related);
        models.when(() -> ModelManager.existField(model, field)).thenReturn(true);
        models.when(() -> ModelManager.getModelField(model, field)).thenReturn(f);
    }

    private Filters guardedOn(String model, Filters filters) {
        return as(() -> service.appendScopeAccessFiltersIgnoringRowScope(model, filters));
    }

    @Test
    @DisplayName("a condition reaching pay through a relation matches nothing when no role grants pay")
    void aPathIntoAnUngrantedFieldMatchesNothing() {
        relationsLeadToEmployee();
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW), ScopeType.ALL, null, false)));

        assertThat(guardedOn("EmpLeave", Filters.of("employeeId.basePay", Operator.GREATER_THAN, 5000)))
                .isEqualTo(ScopeRuleCompiler.matchNone());
        assertThat(guardedOn("EmpLeaveDay", Filters.of("leaveId.employeeId.basePay", Operator.GREATER_THAN, 5000)))
                .isEqualTo(ScopeRuleCompiler.matchNone());
    }

    @Test
    @DisplayName("a condition reaching pay through a relation matches only employees whose pay the caller sees")
    void aPathIntoAPerRowFieldIsBoundToTheGrantingRolesRows() {
        relationsLeadToEmployee();
        holdTheExampleRoles();
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenReturn(List.of(1L));
        when(modelService.getIds(eq("EmpLeave"), any(Filters.class))).thenReturn(List.of(7L));

        Filters oneHop = guardedOn("EmpLeave", Filters.of("employeeId.basePay", Operator.GREATER_THAN, 5000));
        Filters twoHops = guardedOn("EmpLeaveDay",
                Filters.of("leaveId.employeeId.basePay", Operator.GREATER_THAN, 5000));

        assertThat(oneHop).isEqualTo(Filters.of("employeeId", Operator.IN, List.of(1L)));
        // Two hops cross a leave on the way, which the caller reads under a range too: the leaves are the
        // ones inside it whose employee is among those.
        assertThat(twoHops).isEqualTo(Filters.of("leaveId", Operator.IN, List.of(7L)));
        // The employees asked for: the condition, on Employee, AND the rows of R2 — the role granting pay.
        ArgumentCaptor<Filters> asked = ArgumentCaptor.forClass(Filters.class);
        verify(modelService, Mockito.times(2)).getIds(eq("Employee"), asked.capture());
        assertThat(asked.getValue().toString()).contains("basePay", "5000", "employmentType");
        assertThat(asked.getValue().toString()).doesNotContain("employeeId.basePay");
    }

    @Test
    @DisplayName("a declared cascaded field onto pay is judged as the path it stands for")
    void aDeclaredCascadedFieldOntoPayIsJudgedAsItsPath() {
        relationsLeadToEmployee();
        io.softa.framework.orm.meta.MetaField alias = new io.softa.framework.orm.meta.MetaField();
        org.springframework.test.util.ReflectionTestUtils.setField(alias, "fieldName", "employeePay");
        org.springframework.test.util.ReflectionTestUtils.setField(alias, "cascadedField", "employeeId.basePay");
        models.when(() -> ModelManager.existField("EmpLeave", "employeePay")).thenReturn(true);
        models.when(() -> ModelManager.getModelField("EmpLeave", "employeePay")).thenReturn(alias);
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW), ScopeType.ALL, null, false)));

        assertThat(guardedOn("EmpLeave", Filters.of("employeePay", Operator.GREATER_THAN, 5000)))
                .isEqualTo(ScopeRuleCompiler.matchNone());
    }

    @Test
    @DisplayName("a path reaching no granted employee matches nothing")
    void aPathReachingNoVisibleRowMatchesNothing() {
        relationsLeadToEmployee();
        holdTheExampleRoles();
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenReturn(List.of());

        assertThat(guardedOn("EmpLeave", Filters.of("employeeId.basePay", Operator.GREATER_THAN, 5000)))
                .isEqualTo(ScopeRuleCompiler.matchNone());
    }

    @Test
    @DisplayName("a path into pay is left alone for a caller who sees pay on every employee")
    void aPathIntoAUniformlyVisibleFieldIsUntouched() {
        relationsLeadToEmployee();
        pi.setRoleGrants(List.of(role(4, Set.of(VIEW), ScopeType.ALL, null, true)));
        Filters condition = Filters.of("employeeId.basePay", Operator.GREATER_THAN, 5000);

        assertThat(guardedOn("EmpLeave", condition)).isSameAs(condition);
    }

    @Test
    @DisplayName("a path into an ordinary field, or one the guard cannot follow, is left alone")
    void ordinaryAndUnfollowablePathsAreUntouched() {
        relationsLeadToEmployee();
        holdTheExampleRoles();
        Filters ordinary = Filters.of("employeeId.name", Operator.EQUAL, "E1");
        Filters unknownSegment = Filters.of("ghostId.basePay", Operator.GREATER_THAN, 5000);
        // To-many paths are not followed: only many-to-one / one-to-one hops are resolved.
        Filters toMany = Filters.of("approverIds.basePay", Operator.GREATER_THAN, 5000);

        assertThat(guardedOn("EmpLeave", ordinary)).isSameAs(ordinary);
        assertThat(guardedOn("EmpLeave", unknownSegment)).isSameAs(unknownSegment);
        assertThat(guardedOn("EmpLeave", toMany)).isSameAs(toMany);
        verify(modelService, never()).getIds(anyString(), any(Filters.class));
    }

    @Test
    @DisplayName("only the masked branch of a tree is rewritten")
    void aTreeKeepsItsOtherBranches() {
        relationsLeadToEmployee();
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW), ScopeType.ALL, null, false)));
        Filters name = Filters.of("name", Operator.EQUAL, "Annual");
        Filters tree = Filters.or(name, Filters.of("employeeId.basePay", Operator.GREATER_THAN, 5000));

        Filters guarded = guardedOn("EmpLeave", tree);

        assertThat(guarded.getChildren()).containsExactly(name, ScopeRuleCompiler.matchNone());
    }

    @Test
    @DisplayName("a sort through a relation onto pay is dropped and a grouping by it refused")
    void aSortOrGroupingThroughARelationFollowsTheTargetsPlan() {
        relationsLeadToEmployee();
        holdTheExampleRoles();
        io.softa.framework.orm.domain.FlexQuery sorted = new io.softa.framework.orm.domain.FlexQuery();
        sorted.setOrders(io.softa.framework.orm.domain.Orders.ofDesc("employeeId.basePay").addAsc("employeeId.name"));
        io.softa.framework.orm.domain.FlexQuery grouped = new io.softa.framework.orm.domain.FlexQuery();
        grouped.setGroupBy(List.of("leaveId.employeeId.basePay"));

        as(() -> {
            service.guardQuery("EmpLeave", sorted);
            return null;
        });

        assertThat(sorted.getOrders().getFields()).containsExactly("employeeId.name");
        assertThatThrownBy(() -> as(() -> {
            service.guardQuery("EmpLeaveDay", grouped);
            return null;
        })).isInstanceOf(PermissionException.class);
    }

    @Test
    @DisplayName("a sort through a relation onto pay stays for a caller who sees pay on every employee")
    void aSortThroughARelationOntoAUniformlyVisibleFieldStays() {
        relationsLeadToEmployee();
        pi.setRoleGrants(List.of(role(4, Set.of(VIEW), ScopeType.ALL, null, true)));
        io.softa.framework.orm.domain.FlexQuery sorted = new io.softa.framework.orm.domain.FlexQuery();
        sorted.setOrders(io.softa.framework.orm.domain.Orders.ofDesc("employeeId.basePay"));

        as(() -> {
            service.guardQuery("EmpLeave", sorted);
            return null;
        });

        assertThat(sorted.getOrders().getFields()).containsExactly("employeeId.basePay");
    }

    // ─── fields never visible ───

    @Test
    @DisplayName("fields no role grants are listed per model as never visible")
    void fieldsNoRoleGrantsAreHidden() {
        SensitiveFieldSetCache sets = (SensitiveFieldSetCache) org.springframework.test.util.ReflectionTestUtils
                .getField(service, "sfsCache");
        when(sets.modelsWithSensitiveFields()).thenReturn(Set.of("Employee"));
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW), ScopeType.ALL, null, false)));

        assertThat(as(service::getHiddenSensitiveFields)).containsOnlyKeys("Employee");
        assertThat(as(service::getHiddenSensitiveFields).get("Employee")).containsExactlyInAnyOrderElementsOf(PAY);
    }

    @Test
    @DisplayName("a field some role grants, even on some rows only, is not listed")
    void aFieldGrantedOnSomeRowsIsNotHidden() {
        SensitiveFieldSetCache sets = (SensitiveFieldSetCache) org.springframework.test.util.ReflectionTestUtils
                .getField(service, "sfsCache");
        when(sets.modelsWithSensitiveFields()).thenReturn(Set.of("Employee"));
        holdTheExampleRoles();

        assertThat(as(service::getHiddenSensitiveFields)).isEmpty();
    }

    @Test
    @DisplayName("an admin has nothing hidden")
    void anAdminHasNothingHidden() {
        SensitiveFieldSetCache sets = (SensitiveFieldSetCache) org.springframework.test.util.ReflectionTestUtils
                .getField(service, "sfsCache");
        when(sets.modelsWithSensitiveFields()).thenReturn(Set.of("Employee"));
        pi.setRoleCodes(Set.of(io.softa.framework.base.enums.BuiltinRole.TENANT_ADMIN.getCode()));

        assertThat(as(service::getHiddenSensitiveFields)).isEmpty();
    }

    // ─── nulls in a write payload ───

    @Test
    @DisplayName("a payload giving pay as null is not refused up front; a value is")
    void aNullForAnUngrantedFieldIsNotRefusedBeforeTheRowsAreKnown() {
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW, CREATE), ScopeType.ALL, null, false)));
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("name", "E1");
        withNull.put("basePay", null);

        as(() -> {
            service.checkWritePayload("Employee", withNull);
            return null;
        });
        assertThatThrownBy(() -> as(() -> {
            service.checkWritePayload("Employee", Map.of("basePay", 1800));
            return null;
        })).isInstanceOf(PermissionException.class);
    }

    @Test
    @DisplayName("an update clearing pay without the set is still refused once the field is handed over as written")
    void clearingAnUngrantedFieldIsRefused() {
        pi.setRoleGrants(List.of(role(1, Set.of(VIEW, UPDATE), ScopeType.MANAGED_DEPARTMENTS, null, false)));
        when(modelService.count(eq("Employee"), any(Filters.class))).thenReturn(1L);

        assertThatThrownBy(() -> as(() -> {
            service.checkIdsFieldsAccess("Employee", List.of(1L), Set.of("basePay"), AccessType.UPDATE);
            return null;
        })).isInstanceOf(PermissionException.class)
                .hasMessage("You don't have permission to edit Pay fields for this employee.");
    }

    @Test
    @DisplayName("the guarded fields are the model's sensitive ones, and none for an admin")
    void theWriteGuardedFieldsAreTheSensitiveOnes() {
        pi.setRoleGrants(List.of(role(3, Set.of(VIEW), ScopeType.ALL, null, false)));
        assertThat(as(() -> service.getWriteGuardedFields("Employee"))).isEqualTo(PAY);

        pi.setRoleCodes(Set.of(io.softa.framework.base.enums.BuiltinRole.TENANT_ADMIN.getCode()));
        assertThat(as(() -> service.getWriteGuardedFields("Employee"))).isEmpty();
    }

    // ─── one request works each answer out once ───

    private void inRequest(Runnable work) {
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest()));
        try {
            work.run();
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    private ScopeRuleCompiler compiler() {
        return (ScopeRuleCompiler) org.springframework.test.util.ReflectionTestUtils.getField(service, "scopeCompiler");
    }

    private SensitiveFieldSetCache sets() {
        return (SensitiveFieldSetCache) org.springframework.test.util.ReflectionTestUtils.getField(service, "sfsCache");
    }

    private int compiles() {
        return Mockito.mockingDetails(compiler()).getInvocations().size();
    }

    private int planReads() {
        return (int) Mockito.mockingDetails(sets()).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("grantedFieldsFor")).count();
    }

    /** A list read the way the pipeline reads one: the guard, the field filter, the scope, the mask. */
    private void readAList() {
        io.softa.framework.orm.domain.FlexQuery query = new io.softa.framework.orm.domain.FlexQuery();
        query.setOrders(io.softa.framework.orm.domain.Orders.ofDesc("basePay"));
        as(() -> {
            service.guardQuery("Employee", query);
            service.filterReadableFields("Employee", List.of("id", "basePay"), AccessType.READ);
            service.appendScopeAccessFilters("Employee", Filters.of("basePay", Operator.GREATER_THAN, 5000));
            service.maskResponseValue("Employee", new ArrayList<>(List.of(row(1, 1800), row(2, 6000))),
                    AccessType.READ);
            return null;
        });
    }

    @Test
    @DisplayName("within one request the field plan and each grant's rows are worked out once")
    void oneRequestWorksTheFieldPlanAndTheGrantRowsOutOnce() {
        holdTheExampleRoles();
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenReturn(List.of(1L));

        inRequest(() -> {
            readAList();
            int compiles = compiles();
            int planReads = planReads();
            readAList();
            assertThat(compiles()).isEqualTo(compiles);
            assertThat(planReads()).isEqualTo(planReads);
        });
    }

    @Test
    @DisplayName("off a request nothing is remembered, and a new request starts afresh")
    void nothingIsRememberedOffARequestOrAcrossRequests() {
        holdTheExampleRoles();
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenReturn(List.of(1L));

        readAList();
        int afterOne = compiles();
        readAList();
        assertThat(compiles()).isEqualTo(2 * afterOne);

        int before = compiles();
        inRequest(this::readAList);
        int firstRequest = compiles() - before;
        assertThat(firstRequest).isLessThan(afterOne);
        inRequest(this::readAList);
        assertThat(compiles() - before).isEqualTo(2 * firstRequest);
    }

    @Test
    @DisplayName("a write check forgets the grant rows, which depend on data, but keeps the field plan")
    void aWriteCheckForgetsTheGrantRows() {
        holdTheExampleRoles();

        inRequest(() -> {
            as(() -> service.appendScopeAccessFilters("Employee", new Filters()));
            int compiles = compiles();
            int planReads = planReads();
            as(() -> {
                service.checkWritePayload("Employee", Map.of("name", "E1"));
                return null;
            });
            as(() -> service.appendScopeAccessFilters("Employee", new Filters()));
            assertThat(compiles()).isGreaterThan(compiles);
            as(() -> service.filterReadableFields("Employee", List.of("basePay"), AccessType.READ));
            assertThat(planReads()).isGreaterThan(planReads);
            int afterPlan = planReads();
            as(() -> service.filterReadableFields("Employee", List.of("basePay"), AccessType.READ));
            assertThat(planReads()).isEqualTo(afterPlan);
        });
    }

    @Test
    @DisplayName("a remembered scope is handed out as a copy, so a query rewriting it cannot change the next")
    void aRememberedScopeIsACopy() {
        holdTheExampleRoles();

        inRequest(() -> {
            Filters first = as(() -> service.appendScopeAccessFilters("Employee", new Filters()));
            Filters second = as(() -> service.appendScopeAccessFilters("Employee", new Filters()));
            assertThat(second).isEqualTo(first).isNotSameAs(first);
        });
    }
}
