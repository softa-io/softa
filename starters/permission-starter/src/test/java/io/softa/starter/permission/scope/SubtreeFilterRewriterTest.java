package io.softa.starter.permission.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import java.io.Serializable;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

/**
 * `CHILD OF` on a reference to a tree means "that row or anything under it".
 *
 * <p>The operator alone does not mean that. It compiles to {@code LIKE '<value>%'} on the column it
 * names, so left on a ToOne holding an id it produces {@code department_id LIKE '873%'} — a numeric
 * coincidence, not a tree walk. The tree is in the related model's {@code idPath}, so the rewrite has
 * to move the condition there and turn the ids into paths on the way.
 *
 * <p>Two of the cases below guard failures that return MORE rows than asked for, which is the
 * dangerous direction and the one no one notices:
 *
 * <ul>
 *   <li>the separator — {@code LIKE '1/12%'} also matches the unrelated {@code 1/120};
 *   <li>an unresolvable id — dropping the condition would widen the result to everything.
 * </ul>
 */
class SubtreeFilterRewriterTest {

    private static final String MODEL = "Employee";

    private ModelService<Serializable> modelService;
    private SubtreeFilterRewriter rewriter;
    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        rewriter = new SubtreeFilterRewriter(modelService);
        modelManager = Mockito.mockStatic(ModelManager.class);
        declareField("departmentId", FieldType.MANY_TO_ONE, "Department");
        declareField("additionalDepartmentId", FieldType.MANY_TO_ONE, "Department");
        declareField("jobPositionId", FieldType.MANY_TO_ONE, "JobPosition");
        declareField("idPath", FieldType.STRING, null);
        // The structural test the rewriter applies: a relation qualifies when its target stores a
        // path AND points at itself. Department does; JobPosition does not.
        declareTree("Department");
        declareFlat("JobPosition");
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    @DisplayName("a relation to a model that stores no path is left alone")
    void leavesNonTreeRelationsAlone() {
        // What keeps the operator honest now that no model name is hardcoded: the rewrite applies
        // where a prefix match can mean "under", and nowhere else. A reference to a job position
        // has no path to match against, so the condition stays as written rather than being moved
        // onto a column that does not exist.
        Filters original = childOf("jobPositionId", "7");

        Filters out = rewriter.rewrite(MODEL, original);

        assertSame(original, out);
        verifyNoInteractions(modelService);
    }

    /** The rows the tree lookup finds for the ids the filter named. */
    private void stubPaths(String... idPaths) {
        when(modelService.searchList(anyString(), any(FlexQuery.class)))
                .thenReturn(java.util.Arrays.stream(idPaths)
                        .map(p -> java.util.Map.<String, Object>of("idPath", p))
                        .toList());
    }

    /** A model that stores a path and points at itself — what makes a prefix match mean "under". */
    private void declareTree(String modelName) {
        MetaField path = mock(MetaField.class);
        when(path.getFieldType()).thenReturn(FieldType.STRING);
        MetaField parent = mock(MetaField.class);
        when(parent.getFieldType()).thenReturn(FieldType.MANY_TO_ONE);
        when(parent.getRelatedModel()).thenReturn(modelName);
        modelManager.when(() -> ModelManager.existModel(modelName)).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(modelName, "idPath")).thenReturn(path);
        modelManager.when(() -> ModelManager.getModelFields(modelName)).thenReturn(List.of(path, parent));
    }

    /** A model with no path column: a reference to it is an ordinary relation. */
    private void declareFlat(String modelName) {
        modelManager.when(() -> ModelManager.existModel(modelName)).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(modelName, "idPath")).thenReturn(null);
        modelManager.when(() -> ModelManager.getModelFields(modelName)).thenReturn(List.of());
    }

    /** MetaField's setters are package-private, so the catalog entry is stubbed rather than built. */
    private void declareField(String name, FieldType type, String relatedModel) {
        MetaField field = mock(MetaField.class);
        when(field.getFieldType()).thenReturn(type);
        when(field.getRelatedModel()).thenReturn(relatedModel);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(MODEL, name)).thenReturn(field);
    }

    @Test
    @DisplayName("forces a separator, so a sibling sharing the numeric prefix is excluded")
    void appendsSeparatorToDescendantBranch() {
        stubPaths("1/12");

        String sql = rewriter.rewrite(MODEL, childOf("departmentId", "12")).toString();

        // '1/12/' — without the trailing slash this also matches department 1/120.
        assertTrue(sql.contains("1/12/"), () -> "descendant branch lost its separator: " + sql);
    }

    @Test
    @DisplayName("includes the root itself, matching DEPT_SUBTREE")
    void includesTheRootDepartment() {
        stubPaths("1/12");

        String sql = rewriter.rewrite(MODEL, childOf("departmentId", "12")).toString();

        // Two branches: equality on the root, prefix on its descendants.
        assertTrue(sql.contains("departmentId.idPath"), () -> "condition did not move onto idPath: " + sql);
        assertTrue(sql.contains("OR"), () -> "root branch missing, only descendants matched: " + sql);
    }

    @Test
    @DisplayName("moves the condition onto the field the caller named, not the model's anchor")
    void staysOnTheFieldTheCallerNamed() {
        stubPaths("1/12");

        String sql = rewriter.rewrite(MODEL, childOf("additionalDepartmentId", "12")).toString();

        // Employee reaches Department through departmentId, but the caller asked about the other
        // one. Resolving the model's anchor here would answer a different question.
        assertTrue(sql.contains("additionalDepartmentId.idPath"), () -> sql);
        assertFalse(sql.contains("\"departmentId.idPath\""), () -> "rewritten onto the anchor: " + sql);
    }

    @Test
    @DisplayName("matches no rows when the department cannot be resolved, rather than dropping the filter")
    void failsClosedOnAnUnresolvableDepartment() {
        // Unknown / soft-deleted / another tenant's — the resolver returns nothing.
        stubPaths();

        Filters result = rewriter.rewrite(MODEL, childOf("departmentId", "999"));

        assertEquals(ScopeRuleCompiler.matchNone().toString(), result.toString(),
                "an unresolvable department must not widen the result to everything");
    }

    @Test
    @DisplayName("ORs the subtrees together when several departments are picked")
    void combinesSeveralDepartments() {
        stubPaths("1/12", "1/34");

        String sql = rewriter.rewrite(MODEL, childOf("departmentId", List.of("12", "34"))).toString();

        assertTrue(sql.contains("1/12/"), () -> sql);
        assertTrue(sql.contains("1/34/"), () -> sql);
    }

    @Test
    @DisplayName("leaves CHILD OF alone when the field is not a department reference")
    void ignoresNonDepartmentFields() {
        Filters original = childOf("jobPositionId", "7");

        assertSame(original, rewriter.rewrite(MODEL, original));
        verifyNoInteractions(modelService);
    }

    @Test
    @DisplayName("leaves a filter that already names idPath alone")
    void ignoresAnIdPathFilter() {
        // A scope contributor emits exactly this; rewriting it again would append a second .idPath.
        Filters original = childOf("idPath", "1/12/");

        assertSame(original, rewriter.rewrite(MODEL, original));
        verifyNoInteractions(modelService);
    }

    @Test
    @DisplayName("returns filters carrying no CHILD OF untouched, without resolving anything")
    void leavesOrdinaryFiltersUntouched() {
        Filters original = Filters.of("name", io.softa.framework.base.enums.Operator.EQUAL, "Ada");

        assertSame(original, rewriter.rewrite(MODEL, original));
        verifyNoInteractions(modelService);
    }

    @Test
    @DisplayName("rewrites inside a nested tree and keeps the surrounding structure")
    void rewritesWithinANestedTree() {
        stubPaths("1/12");

        Filters nested = Filters.and(
                Filters.of("name", io.softa.framework.base.enums.Operator.EQUAL, "Ada"),
                childOf("departmentId", "12"));

        String sql = rewriter.rewrite(MODEL, nested).toString();

        assertTrue(sql.contains("departmentId.idPath"), () -> sql);
        assertTrue(sql.contains("Ada"), () -> "the sibling condition was lost: " + sql);
    }

    private static Filters childOf(String field, Object value) {
        return new Filters().childOf(field, value instanceof List<?> list ? list : List.of(value));
    }
}
