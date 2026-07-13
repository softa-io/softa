package io.softa.starter.flow.runtime.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * Two-tier variable scope for a flow execution.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────┐
 * │ input   – immutable trigger payload (entity fields, │
 * │           form data, API body, etc.)                │
 * ├─────────────────────────────────────────────────────┤
 * │ vars    – mutable flow variables; nodes write here  │
 * │           via outputVariable / outputMapping        │
 * └─────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Expression access</h3>
 * <ul>
 *   <li>{@code {{ amount }}} — resolved via {@link #resolve(String)}: checks {@code vars} first,
 *       then {@code input}</li>
 *   <li>{@code {{ input.entityId }}} — explicit input access</li>
 * </ul>
 */
@Getter
public class FlowVariableContext {

    /** Immutable trigger payload. */
    @JsonProperty("input")
    private final Map<String, Object> input;

    /** Mutable flow variables. */
    @JsonProperty("vars")
    private final Map<String, Object> vars;

    public FlowVariableContext() {
        this.input = new LinkedHashMap<>();
        this.vars  = new LinkedHashMap<>();
    }

    @JsonCreator
    public FlowVariableContext(
            @JsonProperty("input") Map<String, Object> input,
            @JsonProperty("vars")  Map<String, Object> vars) {
        this.input = input != null ? new LinkedHashMap<>(input) : new LinkedHashMap<>();
        this.vars  = vars  != null ? new LinkedHashMap<>(vars)  : new LinkedHashMap<>();
    }

    // ── Resolution ────────────────────────────────────────────────────────────

    /**
     * Short-path resolution for bare variable names used in {@code {{ expr }}}.
     * Checks {@code vars} first, then {@code input}.
     */
    public Object resolve(String name) {
        if (vars.containsKey(name)) {
            return vars.get(name);
        }
        return input.get(name);
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
     * Return an unmodifiable view of the {@code input} map.
     */
    public Map<String, Object> inputView() {
        return Collections.unmodifiableMap(input);
    }

    /**
     * Return an unmodifiable view of the {@code vars} map.
     */
    public Map<String, Object> varsView() {
        return Collections.unmodifiableMap(vars);
    }
}
