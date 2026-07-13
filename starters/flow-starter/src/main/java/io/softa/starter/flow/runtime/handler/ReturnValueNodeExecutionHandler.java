package io.softa.starter.flow.runtime.handler;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.nodeconfig.ReturnValueNodeConfig;

/**
 * Handler for {@link FlowNodeType#RETURN_VALUE} nodes.
 * <p>
 * Replaces the former {@code ReturnDataNodeExecutionHandler}. Evaluates each
 * entry in {@link ReturnValueNodeConfig#getOutputExpressions()} against the
 * current {@link FlowVariableContext} and returns them as a
 * {@link NodeOutcome.Ended} — the outputs double as the flow's return envelope,
 * and the engine terminates the instance after recording them.
 * </p>
 *
 * <p>Config example:
 * <pre>{@code
 * {
 *   "outputExpressions": {
 *     "approvedAmount": "totalAmount",
 *     "message": "\"Approved by \" + approverName"
 *   }
 * }
 * }</pre>
 */
@Component
public class ReturnValueNodeExecutionHandler implements NodeExecutionHandler {

    @Override
    public boolean supports(FlowNodeType flowNodeType) {
        return FlowNodeType.RETURN_VALUE.equals(flowNodeType);
    }

    @Override
    public NodeOutcome execute(CompiledFlowNode node, FlowVariableContext ctx) {
        Map<String, Object> scope = ctx.toExpressionScope();
        Map<String, Object> outputs = new LinkedHashMap<>();

        if (node.getParsedConfig() instanceof ReturnValueNodeConfig cfg
                && cfg.getOutputExpressions() != null) {
            cfg.getOutputExpressions().forEach((key, expression) -> {
                Object value = ComputeUtils.execute(expression, new LinkedHashMap<>(scope));
                outputs.put(key, value);
            });
        }

        return new NodeOutcome.Ended(outputs);
    }
}
