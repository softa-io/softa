package io.softa.starter.flow.runtime.engine;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.NodeErrorStrategy;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.handler.NodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.NodeOutcome;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;

/**
 * Executes a single node handler with the per-node error strategy
 * (FAIL / RETRY) applied.
 */
@Component
public class NodeRetryExecutor {

    private final FlowAuditService auditService;

    public NodeRetryExecutor(FlowAuditService auditService) {
        this.auditService = auditService;
    }

    public NodeOutcome execute(NodeExecutionHandler handler,
                                       CompiledFlowNode node,
                                       FlowVariableContext ctx,
                                       FlowExecutionState state,
                                       CompiledFlowDefinition definition) {
        NodeErrorStrategy strategy = resolveErrorStrategy(node);
        int maxRetries = strategy == NodeErrorStrategy.RETRY ? resolveRetryCount(node) : 0;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return handler.execute(node, ctx);
            } catch (RuntimeException e) {
                if (strategy == NodeErrorStrategy.RETRY && attempt < maxRetries) {
                    auditService.addTrace(state, definition.getFlowCode(), node,
                            FlowTraceEventType.NODE_ERROR_RETRIED,
                            "Node " + node.getNodeId() + " retry " + (attempt + 1) + "/" + maxRetries
                                    + ": " + e.getMessage());
                    continue;
                }
                throw e;
            }
        }
        throw new FlowRuntimeException("Node " + node.getNodeId() + " execution exhausted all retries");
    }

    private NodeErrorStrategy resolveErrorStrategy(CompiledFlowNode node) {
        if (node.getErrorConfig() == null) {
            return NodeErrorStrategy.FAIL;
        }
        return node.getErrorConfig().getStrategy();
    }

    private int resolveRetryCount(CompiledFlowNode node) {
        if (node.getErrorConfig() == null) {
            return 0;
        }
        return node.getErrorConfig().getRetryCount();
    }
}
