package io.softa.starter.flow.runtime.context;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;

/**
 * Two-tier variable scope for a flow execution.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────┐
 * │ input   – a subflow's input, as its Subflow node    │
 * │           maps it; empty for a root instance        │
 * ├─────────────────────────────────────────────────────┤
 * │ vars    – the run's variables: the instance's own   │
 * │           map, which nodes write their outputs to   │
 * └─────────────────────────────────────────────────────┘
 * </pre>
 *
 * {@code vars} is the map handed in, not a copy: what a node writes is the instance's from then on, with nothing to
 * copy back. Expressions read one flat scope ({@link #toExpressionScope}): {@code {{ amount }}} is {@code vars} first,
 * then {@code input}.
 */
@Getter
public class FlowVariableContext {

    /** A subflow's input. */
    private final Map<String, Object> input;

    /** The run's variables: the map handed in, written in place. */
    private final Map<String, Object> vars;

    public FlowVariableContext() {
        this.input = new LinkedHashMap<>();
        this.vars  = new LinkedHashMap<>();
    }

    /**
     * @param input a subflow's input, copied
     * @param vars  the run's variables, used as given — the instance's own map, so it must be mutable once a node
     *              writes to it
     */
    public FlowVariableContext(Map<String, Object> input, Map<String, Object> vars) {
        this.input = input != null ? new LinkedHashMap<>(input) : new LinkedHashMap<>();
        this.vars  = vars  != null ? vars : new LinkedHashMap<>();
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /**
     * Write a value into the run's variables. Called by the engine with a node's outputs, and with a subflow's
     * results ({@code outputVariable} / {@code outputMapping}).
     */
    public void writeVar(String key, Object value) {
        vars.put(key, value);
    }

    // ── Expression scope ──────────────────────────────────────────────────────

    /**
     * Build the flat variable map passed to AviatorScript / Pebble expressions.
     * Merges {@code input} (lower priority) with {@code vars} (higher priority).
     */
    public Map<String, Object> toExpressionScope() {
        Map<String, Object> scope = new LinkedHashMap<>(input);
        scope.putAll(vars);
        return scope;
    }
}
