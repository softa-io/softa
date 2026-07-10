package io.softa.starter.user.scope;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.enums.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeApplicabilityResolverTest {

    /** Contributor stub — reports applicability via `applicableFields()` and
     *  the default `isApplicableTo` from the interface. */
    static class FieldBasedContributor implements ScopeContributor {
        private final ScopeType type;
        private final List<String> anchors;
        FieldBasedContributor(ScopeType type, String... anchors) {
            this.type = type;
            this.anchors = List.of(anchors);
        }
        @Override public ScopeType scopeType() { return type; }
        @Override public List<String> applicableFields() { return anchors; }
        @Override public Filters compile(ScopeRule r, String m) { return new Filters(); }
    }

    /** Contributor that overrides isApplicableTo to be universal — like
     *  Custom / CreatedBySelf in production. */
    static class UniversalContributor implements ScopeContributor {
        private final ScopeType type;
        UniversalContributor(ScopeType type) { this.type = type; }
        @Override public ScopeType scopeType() { return type; }
        @Override public List<String> applicableFields() { return List.of(); }
        @Override public boolean isApplicableTo(String m, Set<String> f) { return true; }
        @Override public Filters compile(ScopeRule r, String m) { return new Filters(); }
    }

    private static MetaField field(String name) {
        MetaField mf = new MetaField();
        // setFieldName is package-private — poke via ReflectionTestUtils.
        org.springframework.test.util.ReflectionTestUtils.setField(mf, "fieldName", name);
        return mf;
    }

    @Test
    void applicableFor_alwaysIncludesAll() {
        ScopeApplicabilityResolver resolver = new ScopeApplicabilityResolver(List.of());
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee"))
                    .thenReturn(List.of(field("id")));
            assertThat(resolver.applicableFor("Employee")).containsExactly(ScopeType.ALL);
        }
    }

    @Test
    void applicableFor_unknownModel_returnsOnlyAll() {
        ScopeApplicabilityResolver resolver = new ScopeApplicabilityResolver(
                List.of(new FieldBasedContributor(ScopeType.SELF, "employeeId")));
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Unknown")).thenReturn(false);
            assertThat(resolver.applicableFor("Unknown")).containsExactly(ScopeType.ALL);
        }
    }

    @Test
    void applicableFor_nullModel_returnsOnlyAll() {
        ScopeApplicabilityResolver resolver = new ScopeApplicabilityResolver(
                List.of(new FieldBasedContributor(ScopeType.SELF, "employeeId")));
        assertThat(resolver.applicableFor(null)).containsExactly(ScopeType.ALL);
    }

    @Test
    void applicableFor_fieldBasedContributor_appliesWhenAnchorPresent() {
        ScopeApplicabilityResolver resolver = new ScopeApplicabilityResolver(
                List.of(new FieldBasedContributor(ScopeType.SELF, "employeeId")));
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("LeaveRequest")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("LeaveRequest"))
                    .thenReturn(List.of(field("employeeId"), field("startDate")));

            Set<ScopeType> types = resolver.applicableFor("LeaveRequest");
            assertThat(types).containsExactlyInAnyOrder(ScopeType.ALL, ScopeType.SELF);
        }
    }

    @Test
    void applicableFor_fieldBasedContributor_skippedWhenAnchorAbsent() {
        ScopeApplicabilityResolver resolver = new ScopeApplicabilityResolver(
                List.of(new FieldBasedContributor(ScopeType.SELF, "employeeId")));
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Department")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Department"))
                    .thenReturn(List.of(field("id"), field("name")));

            assertThat(resolver.applicableFor("Department")).containsExactly(ScopeType.ALL);
        }
    }

    @Test
    void applicableFor_universalContributor_alwaysIncluded() {
        ScopeApplicabilityResolver resolver = new ScopeApplicabilityResolver(
                List.of(new UniversalContributor(ScopeType.CUSTOM)));
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Department")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Department"))
                    .thenReturn(List.of(field("id"), field("name")));

            assertThat(resolver.applicableFor("Department"))
                    .containsExactlyInAnyOrder(ScopeType.ALL, ScopeType.CUSTOM);
        }
    }

    @Test
    void applicableFor_multipleContributors_unions() {
        ScopeApplicabilityResolver resolver = new ScopeApplicabilityResolver(List.of(
                new FieldBasedContributor(ScopeType.SELF, "employeeId"),
                new FieldBasedContributor(ScopeType.LEGAL_ENTITY, "legalEntityId"),
                new FieldBasedContributor(ScopeType.DEPT_SUBTREE, "departmentId"),
                new UniversalContributor(ScopeType.CUSTOM)));

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(
                    field("id"), field("legalEntityId"), field("departmentId")));

            Set<ScopeType> types = resolver.applicableFor("Employee");
            // SELF's "employeeId" isn't present → SELF is excluded.
            // LEGAL_ENTITY + DEPT_SUBTREE anchors are present.
            // CUSTOM is universal.
            assertThat(types).containsExactlyInAnyOrder(
                    ScopeType.ALL, ScopeType.LEGAL_ENTITY, ScopeType.DEPT_SUBTREE, ScopeType.CUSTOM);
        }
    }

    @Test
    void applicableFor_multipleAnchorsOnOneContributor_orSemantics() {
        // Contributor lists two anchors — presence of either one qualifies.
        ScopeApplicabilityResolver resolver = new ScopeApplicabilityResolver(
                List.of(new FieldBasedContributor(ScopeType.SELF, "employeeId", "userId")));
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Foo")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Foo"))
                    .thenReturn(List.of(field("userId")));

            assertThat(resolver.applicableFor("Foo"))
                    .containsExactlyInAnyOrder(ScopeType.ALL, ScopeType.SELF);
        }
    }
}
