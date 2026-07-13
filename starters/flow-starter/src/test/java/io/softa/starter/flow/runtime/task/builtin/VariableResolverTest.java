package io.softa.starter.flow.runtime.task.builtin;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.Filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VariableResolver} — the static placeholder/expression
 * resolution helper used by the builtin flow task executors.
 * <p>
 * Cases use the model-independent (modelName == null) paths so that no
 * {@code ModelManager} metadata registry needs to be bootstrapped: constants
 * pass through untouched, variable placeholders are extracted from the
 * execution variables, and expression placeholders are evaluated by the
 * Aviator-backed {@code ComputeUtils}.
 */
class VariableResolverTest {

    @Test
    void resolveDataTemplateMixesConstantsVariablesAndExpressions() {
        Map<String, Object> template = new HashMap<>();
        template.put("name", "literal");                 // plain constant
        template.put("status", "{{ statusVar }}");       // variable placeholder
        template.put("total", "{{ a + b }}");            // expression placeholder
        template.put("count", 42);                       // non-string passthrough

        // Mutable map required: ComputeUtils.execute mutates the env (putAll of
        // ChronoUnit helpers) when evaluating the expression placeholder.
        Map<String, Object> variables = new HashMap<>();
        variables.put("statusVar", "ACTIVE");
        variables.put("a", 1);
        variables.put("b", 2);

        Map<String, Object> result = VariableResolver.resolveDataTemplate(template, variables);

        assertEquals("literal", result.get("name"));
        assertEquals("ACTIVE", result.get("status"));
        // The expression placeholder is evaluated; assert on the numeric value
        // regardless of the concrete Number subtype returned by the engine.
        assertEquals(3L, ((Number) result.get("total")).longValue());
        assertEquals(42, result.get("count"));
    }

    @Test
    void resolveDataTemplateThrowsWhenVariablePlaceholderMissing() {
        Map<String, Object> template = Map.of("status", "{{ missingVar }}");

        assertThrows(IllegalArgumentException.class,
                () -> VariableResolver.resolveDataTemplate(template, Map.of()));
    }

    @Test
    void resolveDataTemplateThrowsWhenExpressionVariableMissing() {
        // Expression depends on `a` and `b`, but only `a` is supplied.
        Map<String, Object> template = Map.of("total", "{{ a + b }}");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> VariableResolver.resolveDataTemplate(template, Map.of("a", 1)));
        assertTrue(ex.getMessage().contains("b"));
    }

    @Test
    void resolveFilterValueReplacesVariablePlaceholderInLeaf() {
        Filters filters = Filters.of("status", Operator.EQUAL, "{{ statusVar }}");

        VariableResolver.resolveFilterValue(null, filters, Map.of("statusVar", "ACTIVE"));

        assertEquals("ACTIVE", filters.getFilterUnit().getValue());
    }

    @Test
    void getIdsFromPkVariableReturnsSingletonForScalar() {
        Collection<?> ids = VariableResolver.getIdsFromPkVariable(
                "{{ TriggerParams.id }}",
                Map.of("TriggerParams", Map.of("id", 100L)));

        assertEquals(1, ids.size());
        assertEquals(100L, ids.iterator().next());
    }

    @Test
    void getIdsFromPkVariableReturnsCollectionAsIs() {
        Collection<?> ids = VariableResolver.getIdsFromPkVariable(
                "{{ ids }}",
                Map.of("ids", List.of(1L, 2L, 3L)));

        assertEquals(List.of(1L, 2L, 3L), List.copyOf(ids));
    }

    @Test
    void getIdsFromPkVariableReturnsEmptyForNullValueOrNonVariable() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("id", null);

        // Variable resolves to null -> empty.
        assertTrue(VariableResolver.getIdsFromPkVariable("{{ id }}", vars).isEmpty());
        // Plain (non-placeholder) string -> not a variable -> empty.
        assertTrue(VariableResolver.getIdsFromPkVariable("not-a-placeholder", Map.of()).isEmpty());
        // Expression placeholder is not a VARIABLE kind -> empty.
        assertTrue(VariableResolver.getIdsFromPkVariable("{{ a + b }}", Map.of("a", 1, "b", 2)).isEmpty());
    }

    @Test
    void resolveFilterValueIgnoresEmptyFilters() {
        // Should be a no-op and must not throw on an empty/null filter tree.
        VariableResolver.resolveFilterValue(null, new Filters(), Map.of());
        assertNull(new Filters().getFilterUnit());
    }
}
