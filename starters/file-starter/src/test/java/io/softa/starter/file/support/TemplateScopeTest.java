package io.softa.starter.file.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.meta.ModelManager;

/**
 * Which models a page's template list may draw from.
 *
 * <p>The base set — own model plus child models — is what lets one employee page hand out the
 * templates for addresses, family members and the rest. But "child model" counts the target of every
 * OneToMany, and a reverse reference looks exactly like a composition there: Employee declares
 * {@code managedDepartments} / {@code hrbpDepartments} for "departments this person leads", so
 * Department became a child of Employee and its import template appeared in the employee dialog
 * (zingkey/zingkey-hcm#764).
 *
 * <p>The fixture below is that exact shape: Employee with two genuine child records and one
 * first-class entity that only points back at it.
 */
class TemplateScopeTest {

    private static final String HOST = "Employee";
    private static final Set<String> CHILDREN =
            Set.of("EmpAddress", "EmpFamilyMember", "Department");

    @Test
    void aStandaloneModelIsDroppedFromAnotherPagesScope() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getChildModels(HOST)).thenReturn(CHILDREN);

            Set<String> scope = TemplateScope.of(HOST, () -> Set.of("Department"));

            assertThat(scope)
                    .as("Department only points back at Employee; its template belongs on its own page")
                    .doesNotContain("Department");
        }
    }

    @Test
    void theGenuineChildRecordsSurvive() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getChildModels(HOST)).thenReturn(CHILDREN);

            Set<String> scope = TemplateScope.of(HOST, () -> Set.of("Department"));

            assertThat(scope)
                    .as("addresses and family members are imported FROM the employee page — that is "
                            + "what child-model templates are for")
                    .contains(HOST, "EmpAddress", "EmpFamilyMember");
        }
    }

    /**
     * A standalone template still belongs on its own model's page — that is the point of it.
     *
     * <p>Given child models of its own, so the exclusion actually runs: Department has a sub-department
     * tree, so its page reaches the loop that a host-blind {@code removeIf(standalone::contains)} would
     * use to delete the very page being asked about.
     */
    @Test
    void theHostSurvivesItsOwnStandaloneMark() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getChildModels("Department"))
                    .thenReturn(Set.of("DepartmentHeadcountStat"));

            Set<String> scope = TemplateScope.of("Department", () -> Set.of("Department"));

            assertThat(scope)
                    .as("the Department page is exactly where the Department template belongs")
                    .contains("Department");
        }
    }

    /**
     * The default. Every template ships unmarked, so an unmarked estate must behave exactly as it did
     * before this attribute existed — including the reverse references, which stay visible until
     * somebody marks them.
     */
    @Test
    void withNothingMarkedTheScopeIsUnchanged() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getChildModels(HOST)).thenReturn(CHILDREN);

            Set<String> scope = TemplateScope.of(HOST, Set::of);

            assertThat(scope).containsExactlyInAnyOrder(
                    "Employee", "EmpAddress", "EmpFamilyMember", "Department");
        }
    }

    /** A page with no child models cannot be polluted, so it must not pay for the lookup. */
    @Test
    void aPageWithNoChildModelsNeverAsksWhichModelsAreStandalone() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getChildModels("JobGrade")).thenReturn(Set.of());
            AtomicInteger lookups = new AtomicInteger();

            Set<String> scope = TemplateScope.of("JobGrade", () -> {
                lookups.incrementAndGet();
                return Set.of();
            });

            assertThat(scope).containsExactly("JobGrade");
            assertThat(lookups).hasValue(0);
        }
    }

    /** getChildModels' contract does not promise a mutable set; adding the host must not touch it. */
    @Test
    void theCallersChildModelSetIsNotModified() {
        Set<String> immutable = Set.of("EmpAddress", "Department");
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getChildModels(HOST)).thenReturn(immutable);

            TemplateScope.of(HOST, () -> Set.of("Department"));

            assertThat(immutable).containsExactlyInAnyOrder("EmpAddress", "Department");
        }
    }
}
