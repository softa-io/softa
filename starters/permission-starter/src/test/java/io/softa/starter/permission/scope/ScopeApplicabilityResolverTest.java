package io.softa.starter.permission.scope;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.permission.spi.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Data-driven applicability (2026-07-16, filter-merged 2026-07-17): the resolver
 * reads {@code DataScopeType} rows (mocked here via {@link DataScopeTypeReader}) and
 * matches each row against the queried model's fields. For identity types the
 * applicable fields are derived from the row's {@code filter} template (and
 * {@code identityFilter} for the model-swap); code-contributor types keep an explicit
 * {@code applicableFields}. Replaces the old per-contributor {@code isApplicableTo}.
 */
class ScopeApplicabilityResolverTest {

    /** Identity-type row: applicability derived from the filter template. */
    private static Map<String, Object> filterRow(String id, List<String> filter,
                                                 String identityModel, List<String> identityFilter) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("filter", filter);
        if (identityModel != null) {
            m.put("identityModel", identityModel);
        }
        if (identityFilter != null) {
            m.put("identityFilter", identityFilter);
        }
        return m;
    }

    /** Code-contributor row (DEPT_SUBTREE / MANAGED_DEPARTMENTS): explicit fields. */
    private static Map<String, Object> codeRow(String id, List<String> applicableFields) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("applicableFields", applicableFields);
        return m;
    }

    private static Map<String, Object> allRow(String id) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("appliesToAll", true);
        return m;
    }

    @SafeVarargs
    private static ScopeApplicabilityResolver resolverWith(Map<String, Object>... rows) {
        DataScopeTypeReader reader = mock(DataScopeTypeReader.class);
        when(reader.read()).thenReturn(List.of(rows));
        ScopeApplicabilityResolver resolver = new ScopeApplicabilityResolver(reader);
        resolver.build();
        return resolver;
    }

    private static MetaField field(String name) {
        MetaField mf = new MetaField();
        // setFieldName is package-private — poke via ReflectionTestUtils.
        org.springframework.test.util.ReflectionTestUtils.setField(mf, "fieldName", name);
        return mf;
    }

    private static Map<String, Object> self() {
        return filterRow("SELF", List.of("employeeId", "=", "USER_EMP_ID"),
                "Employee", List.of("id", "=", "USER_EMP_ID"));
    }

    private static Map<String, Object> directReports() {
        return filterRow("DIRECT_REPORTS", List.of("managerId", "=", "USER_EMP_ID"),
                "Employee", List.of("piEmployeeId", "=", "USER_EMP_ID"));
    }

    private static MetaField owner(String relatedModel) {
        MetaField mf = mock(MetaField.class);
        when(mf.getFieldType()).thenReturn(io.softa.framework.orm.enums.FieldType.MANY_TO_ONE);
        when(mf.getRelatedModel()).thenReturn(relatedModel);
        return mf;
    }

    @Test
    void directReports_reachesARecordThroughTheEmployeeItBelongsTo() {
        ScopeApplicabilityResolver resolver = resolverWith(directReports(), self());
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("LeaveRequest")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("LeaveRequest"))
                    .thenReturn(List.of(field("id"), field("employeeId")));
            MetaField employee = owner("Employee");
            mm.when(() -> ModelManager.getModelFieldOrNull("LeaveRequest", "employeeId")).thenReturn(employee);

            assertThat(resolver.applicableFor("LeaveRequest")).contains(ScopeType.DIRECT_REPORTS, ScopeType.SELF);
        }
    }

    @Test
    void directReports_usesAManagerFieldOnTheRowItself() {
        ScopeApplicabilityResolver resolver = resolverWith(directReports());
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("PreOnboarding")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("PreOnboarding"))
                    .thenReturn(List.of(field("id"), field("piEmployeeId")));

            assertThat(resolver.applicableFor("PreOnboarding")).contains(ScopeType.DIRECT_REPORTS);
        }
    }

    @Test
    void directReports_doesNotApplyWhereNothingLeadsToAManager() {
        ScopeApplicabilityResolver resolver = resolverWith(directReports());
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Department")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Department")).thenReturn(List.of(field("id"), field("idPath")));

            assertThat(resolver.applicableFor("Department")).doesNotContain(ScopeType.DIRECT_REPORTS);
        }
    }

    @Test
    void directReports_doesNotFollowAnEmployeeIdPointingElsewhere() {
        ScopeApplicabilityResolver resolver = resolverWith(directReports());
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Odd")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Odd")).thenReturn(List.of(field("id"), field("employeeId")));
            MetaField other = owner("Other");
            mm.when(() -> ModelManager.getModelFieldOrNull("Odd", "employeeId")).thenReturn(other);

            assertThat(resolver.applicableFor("Odd")).doesNotContain(ScopeType.DIRECT_REPORTS);
        }
    }

    @Test
    void self_doesNotFallBackToAnIdentityAnchorOnId() {
        ScopeApplicabilityResolver resolver = resolverWith(self());
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Currency")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Currency")).thenReturn(List.of(field("id")));

            assertThat(resolver.applicableFor("Currency")).doesNotContain(ScopeType.SELF);
        }
    }

    @Test
    void applicableFor_emptyRegistry_onlyAll() {
        ScopeApplicabilityResolver resolver = resolverWith();
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(field("id")));
            assertThat(resolver.applicableFor("Employee")).containsExactly(ScopeType.ALL);
        }
    }

    @Test
    void applicableFor_unknownModel_returnsOnlyAll() {
        ScopeApplicabilityResolver resolver = resolverWith(self());
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Unknown")).thenReturn(false);
            assertThat(resolver.applicableFor("Unknown")).containsExactly(ScopeType.ALL);
        }
    }

    @Test
    void applicableFor_nullModel_returnsOnlyAll() {
        ScopeApplicabilityResolver resolver = resolverWith(self());
        assertThat(resolver.applicableFor(null)).containsExactly(ScopeType.ALL);
    }

    @Test
    void applicableFor_identityFilter_appliesWhenAnchorPresent() {
        ScopeApplicabilityResolver resolver = resolverWith(self());
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("LeaveRequest")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("LeaveRequest"))
                    .thenReturn(List.of(field("employeeId"), field("startDate")));
            // Non-identity model → derived from filter's LHS field "employeeId".
            assertThat(resolver.applicableFor("LeaveRequest"))
                    .containsExactlyInAnyOrder(ScopeType.ALL, ScopeType.SELF);
        }
    }

    @Test
    void applicableFor_identityFilter_skippedWhenAnchorAbsent() {
        ScopeApplicabilityResolver resolver = resolverWith(self());
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Department")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Department"))
                    .thenReturn(List.of(field("id"), field("name")));
            // No employeeId, and Department != identityModel → SELF excluded.
            assertThat(resolver.applicableFor("Department")).containsExactly(ScopeType.ALL);
        }
    }

    @Test
    void applicableFor_identityModel_matchesViaIdentityFilter() {
        // On the Employee model itself the swap uses identityFilter → field "id",
        // even though the business anchor "employeeId" is absent.
        ScopeApplicabilityResolver resolver = resolverWith(self());
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(field("id")));
            assertThat(resolver.applicableFor("Employee"))
                    .containsExactlyInAnyOrder(ScopeType.ALL, ScopeType.SELF);
        }
    }

    @Test
    void applicableFor_appliesToAll_alwaysIncluded() {
        ScopeApplicabilityResolver resolver = resolverWith(allRow("CUSTOM"));
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Department")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Department"))
                    .thenReturn(List.of(field("id"), field("name")));
            assertThat(resolver.applicableFor("Department"))
                    .containsExactlyInAnyOrder(ScopeType.ALL, ScopeType.CUSTOM);
        }
    }

    @Test
    void applicableFor_multipleRows_union() {
        ScopeApplicabilityResolver resolver = resolverWith(
                self(),
                codeRow("DEPT_SUBTREE", List.of("departmentId")),
                allRow("CUSTOM"));
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(
                    field("id"), field("legalEntityId"), field("departmentId")));
            // SELF matches Employee via identityFilter (id); DEPT_SUBTREE's anchor is present;
            // CUSTOM is universal.
            assertThat(resolver.applicableFor("Employee")).containsExactlyInAnyOrder(
                    ScopeType.ALL, ScopeType.SELF, ScopeType.DEPT_SUBTREE, ScopeType.CUSTOM);
        }
    }

    @Test
    void applicableFor_codeContributor_anyOfApplicableFields() {
        // Code-contributor rows keep OR-over-applicableFields semantics.
        ScopeApplicabilityResolver resolver = resolverWith(
                codeRow("MANAGED_DEPARTMENTS", List.of("departmentId", "orgUnitId")));
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Foo")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Foo")).thenReturn(List.of(field("orgUnitId")));
            assertThat(resolver.applicableFor("Foo"))
                    .containsExactlyInAnyOrder(ScopeType.ALL, ScopeType.MANAGED_DEPARTMENTS);
        }
    }
}
