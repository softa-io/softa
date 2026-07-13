package io.softa.starter.flow.runtime.nodeconfig;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code CREATE_RECORD} nodes.
 * <p>
 * Fields hold the raw authored values ({@code modelName} may itself be a {@code {{ expr }}};
 * {@code rowTemplate} values are placeholders / expressions). Resolution — including
 * field-type-aware coercion of the row template — is owned by the executor via
 * {@code VariableResolver}, not pre-resolved generically.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateDataConfig {

    /** Target model name (required). */
    private String modelName;

    /** Field → value template; values support {@code {{ var }}} / {@code {{ expr }}} (required). */
    private Map<String, Object> rowTemplate;
}
