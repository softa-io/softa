package io.softa.starter.flow.runtime.nodeconfig;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code UPDATE_RECORD} nodes.
 * <p>
 * Rows are targeted by {@code pkVariable} (a {@code {{ var }}} resolving to id(s)) and/or
 * {@code filters}; {@code rowTemplate} holds the new field values. All placeholder / type-aware
 * resolution is owned by the executor via {@code VariableResolver}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateDataConfig {

    /** Target model name (required). */
    private String modelName;

    /** Optional primary-key variable placeholder, e.g. {@code "{{ employeeId }}"}. */
    private String pkVariable;

    /** Optional filter condition; a raw JSON list / filter string coerced to {@code Filters} by the executor. */
    private Object filters;

    /** Field → value template; values support {@code {{ var }}} / {@code {{ expr }}} (required). */
    private Map<String, Object> rowTemplate;
}
