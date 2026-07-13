package io.softa.starter.flow.runtime.handler;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.nodeconfig.ScriptNodeConfig;

/**
 * Handler for {@link FlowNodeType#SCRIPT} nodes.
 * <p>
 * Replaces the former {@code ComputeTaskNodeExecutionHandler}. Evaluates an
 * AviatorScript expression sourced from {@link ScriptNodeConfig#getExpression()}
 * against the current {@link FlowVariableContext} and writes the result under
 * {@link ScriptNodeConfig#getOutputVariable()} into {@code outputs}.
 * </p>
 * <p>
 * Config example:
 * <pre>{@code
 * {
 *   "expression": "totalAmount * (1 - discountRate)",
 *   "outputVariable": "netAmount"
 * }
 * }</pre>
 * If no expression is configured the node completes as a pass-through.
 * </p>
 */
@Component
public class ScriptNodeExecutionHandler implements NodeExecutionHandler {

    @Override
    public boolean supports(FlowNodeType flowNodeType) {
        return FlowNodeType.SCRIPT.equals(flowNodeType);
    }

    @Override
    public NodeOutcome execute(CompiledFlowNode node, FlowVariableContext ctx) {
        if (!(node.getParsedConfig() instanceof ScriptNodeConfig cfg)) {
            return passThrough();
        }
        String expression = cfg.getExpression();
        if (!StringUtils.hasText(expression)) {
            return passThrough();
        }

        Map<String, Object> scope = ctx.toExpressionScope();
        Object result = ComputeUtils.execute(expression, new LinkedHashMap<>(scope));

        String outputVariable = cfg.getOutputVariable();
        Map<String, Object> outputs = new LinkedHashMap<>();
        if (StringUtils.hasText(outputVariable)) {
            outputs.put(outputVariable, result);
        }

        return new NodeOutcome.Completed(outputs);
    }

    private NodeOutcome passThrough() {
        return new NodeOutcome.Completed(Map.of());
    }
}
