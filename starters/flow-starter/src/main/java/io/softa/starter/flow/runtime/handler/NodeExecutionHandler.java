package io.softa.starter.flow.runtime.handler;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;

/**
 * Pluggable handler SPI for runtime node execution.
 */
public interface NodeExecutionHandler {

    boolean supports(FlowNodeType flowNodeType);

    NodeOutcome execute(CompiledFlowNode node, FlowVariableContext ctx);
}
