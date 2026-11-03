package io.softa.starter.flow.runtime.handler;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.nodeconfig.SubflowNodeConfig;

/**
 * Handler for {@link FlowNodeType#SUBFLOW} nodes.
 * <p>
 * Reads the new {@link SubflowNodeConfig} (which contains {@code inputMapping} and
 * {@code outputMapping}) and returns a {@code RunSubflow} outcome to the engine.
 * The engine is responsible for:
 * <ol>
 *   <li>Forking a child {@link FlowVariableContext} via
 *       {@code FlowVariableContextUtils.forkForSubflow()} using {@code inputMapping}</li>
 *   <li>Executing the referenced subflow to completion</li>
 *   <li>Merging the child result back via
 *       {@code FlowVariableContextUtils.mergeSubflowResult()} using
 *       {@code outputVariable} and {@code outputMapping}</li>
 * </ol>
 * </p>
 */
@Component
public class SubflowNodeExecutionHandler implements NodeExecutionHandler {

    @Override
    public boolean supports(FlowNodeType flowNodeType) {
        return FlowNodeType.SUBFLOW.equals(flowNodeType);
    }

    @Override
    public NodeOutcome execute(CompiledFlowNode node, FlowVariableContext ctx) {
        if (!(node.getParsedConfig() instanceof SubflowNodeConfig cfg)
                || cfg.getSubflowId() == null) {
            throw new FlowRuntimeException("Subflow node '" + node.displayName() + "' must configure subflowId");
        }
        return new NodeOutcome.RunSubflow(cfg.getSubflowId(), cfg.getInputMapping(),
                cfg.getOutputVariable(), cfg.getOutputMapping());
    }
}
