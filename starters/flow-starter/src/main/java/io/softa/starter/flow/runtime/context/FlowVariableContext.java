package io.softa.starter.flow.runtime.context;

import java.util.Collections;
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
 * │ vars    – mutable flow variables; nodes write here  │
 * │           via outputVariable / outputMapping        │
 * └─────────────────────────────────────────────────────┘
 * </pre>
 *
 * Expressions read one flat scope ({@link #toExpressionScope}): {@code {{ amount }}} is {@code vars} first, then
 * {@code input}.
 */
@Getter
public class FlowVariableContext {

    /** A subflow's input. */
    private final Map<String, Object> input;

    /** Mutable flow variables. */
    private final Map<String, Object> vars;

    public FlowVariableContext() {
        this.input = new LinkedHashMap<>();
        this.vars  = new LinkedHashMap<>();
    }

    public FlowVariableContext(Map<String, Object> input, Map<String, Object> vars) {
        this.input = input != null ? new LinkedHashMap<>(input) : new LinkedHashMap<>();
        this.vars  = vars  != null ? new LinkedHashMap<>(vars)  : new LinkedHashMap<>();
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /**
     * Write a value into the mutable {@code vars} scope.
     * Called by the engine when a node declares {@code outputVariable} or
     * individual {@code outputMapping} entries.
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

    /**
     * Return an unmodifiable view of the {@code vars} map.
     */
    public Map<String, Object> varsView() {
        return Collections.unmodifiableMap(vars);
    }
}
