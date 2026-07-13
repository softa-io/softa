package io.softa.starter.flow.runtime.nodeconfig;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Config for {@code FlowNodeType.RETURN_VALUE} nodes.
 * <p>
 * Replaces the former {@code ReturnDataNodeConfig}. Declares a map of named
 * output expressions whose evaluated values are written into
 * {@code FlowExecutionState.outputs}.
 *
 * <pre>{@code
 * {
 *   "outputExpressions": {
 *     "approvedAmount": "totalAmount",
 *     "message": "\"Approved by \" + approverName"
 *   }
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnValueNodeConfig {

    /**
     * Map of output key → AviatorScript expression.
     * Each entry is evaluated and written into the flow's {@code outputs} map.
     */
    private Map<String, String> outputExpressions;
}
