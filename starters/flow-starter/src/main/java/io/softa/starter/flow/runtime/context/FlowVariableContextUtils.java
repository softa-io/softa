package io.softa.starter.flow.runtime.context;

import java.util.LinkedHashMap;
import java.util.Map;

import io.softa.framework.orm.compute.ComputeUtils;

/**
 * Factory and merge utilities for {@link FlowVariableContext}.
 *
 * <p>Centralises all context-construction logic so that the orchestrator,
 * subflow handler, and foreach handler don't duplicate variable-scope rules.
 */
public final class FlowVariableContextUtils {

    private FlowVariableContextUtils() {}

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Build an initial context that combines a static trigger payload with
     * extra variables provided by the caller (e.g. initiator-supplied vars
     * from {@code FlowStartRequest.variables}).
     */
    public static FlowVariableContext fromTriggerPayloadAndVars(
            Map<String, Object> payload, Map<String, Object> initialVars) {
        return new FlowVariableContext(payload,
                initialVars != null ? initialVars : Map.of());
    }

    // ── Subflow fork / merge ──────────────────────────────────────────────────

    /**
     * Create a child context for a subflow invocation.
     *
     * <p>The {@code inputMapping} maps child-flow {@code input} keys to
     * AviatorScript expressions evaluated against the parent context.
     * <pre>
     * inputMapping: { "reviewerId": "{{ currentUser }}", "docId": "{{ documentId }}" }
     * </pre>
     *
     * @param parentCtx    the parent flow's variable context
     * @param inputMapping child input key → AviatorScript expression resolved in parent scope
     * @return a fresh context for the subflow; its {@code input} is populated from the mapping
     */
    public static FlowVariableContext forkForSubflow(
            FlowVariableContext parentCtx,
            Map<String, String> inputMapping) {

        Map<String, Object> childInput = new LinkedHashMap<>();
        if (inputMapping != null) {
            Map<String, Object> parentScope = parentCtx.toExpressionScope();
            inputMapping.forEach((childKey, expression) -> {
                Object value = evaluateExpression(expression, parentScope);
                childInput.put(childKey, value);
            });
        }
        return new FlowVariableContext(childInput, Map.of());
    }

    /**
     * Merge a completed subflow's results back into the parent context.
     *
     * <p>Two strategies are supported (both are optional and can be combined):
     * <ul>
     *   <li>{@code outputVariable} — writes the entire child {@code vars} map as a
     *       single object under one parent var key</li>
     *   <li>{@code outputMapping} — fine-grained: parent var key → expression resolved
     *       against the child's expression scope</li>
     * </ul>
     *
     * @param parentCtx      the parent context to write results into
     * @param childCtx       the completed child context
     * @param outputVariable (optional) parent var key to receive the child's full vars
     * @param outputMapping  (optional) parent var key → expression in child scope
     */
    public static void mergeSubflowResult(
            FlowVariableContext parentCtx,
            FlowVariableContext childCtx,
            String outputVariable,
            Map<String, String> outputMapping) {

        if (outputVariable != null && !outputVariable.isBlank()) {
            parentCtx.writeVar(outputVariable, new LinkedHashMap<>(childCtx.getVars()));
        }

        if (outputMapping != null && !outputMapping.isEmpty()) {
            Map<String, Object> childScope = childCtx.toExpressionScope();
            outputMapping.forEach((parentKey, expression) -> {
                Object value = evaluateExpression(expression, childScope);
                parentCtx.writeVar(parentKey, value);
            });
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Object evaluateExpression(String expression, Map<String, Object> scope) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        // Delegate to the framework's AviatorScript / Pebble interpolation utility.
        // String literals wrapped in {{ }} are interpolated; plain expressions are evaluated.
        String trimmed = expression.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            return ComputeUtils.stringInterpolation(trimmed, new LinkedHashMap<>(scope));
        }
        return ComputeUtils.execute(trimmed, new LinkedHashMap<>(scope));
    }
}
