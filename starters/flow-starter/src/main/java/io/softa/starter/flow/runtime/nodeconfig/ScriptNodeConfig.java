package io.softa.starter.flow.runtime.nodeconfig;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Config for {@code FlowNodeType.SCRIPT} nodes.
 * <p>
 * Replaces the former {@code ComputeNodeConfig}. Evaluates an AviatorScript
 * expression and writes the result under {@code outputVariable} in {@code vars}.
 *
 * <pre>{@code
 * {
 *   "expression": "totalAmount * (1 - discountRate)",
 *   "outputVariable": "netAmount"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScriptNodeConfig {

    /** AviatorScript expression to evaluate. Result is stored under {@code outputVariable}. */
    private String expression;

    /** Variable name in {@code vars} where the result is written. */
    private String outputVariable;
}
