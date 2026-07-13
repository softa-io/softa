package io.softa.starter.flow.runtime.handler;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;

/**
 * Spring-backed registry for runtime node handlers.
 * <p>
 * The type→handler map is built once at construction: a node type claimed by two
 * handlers fails startup instead of silently picking one, and lookups are O(1).
 */
@Component
public class DefaultNodeHandlerRegistry {

    private final Map<FlowNodeType, NodeExecutionHandler> handlersByType;

    public DefaultNodeHandlerRegistry(List<NodeExecutionHandler> handlers) {
        Map<FlowNodeType, NodeExecutionHandler> byType = new EnumMap<>(FlowNodeType.class);
        for (FlowNodeType type : FlowNodeType.values()) {
            for (NodeExecutionHandler handler : handlers) {
                if (!handler.supports(type)) {
                    continue;
                }
                NodeExecutionHandler previous = byType.putIfAbsent(type, handler);
                if (previous != null) {
                    throw new IllegalStateException("Duplicate node handlers registered for " + type + ": "
                            + previous.getClass().getName() + " and " + handler.getClass().getName());
                }
            }
        }
        this.handlersByType = byType;
    }

    public NodeExecutionHandler getHandler(FlowNodeType flowNodeType) {
        NodeExecutionHandler handler = handlersByType.get(flowNodeType);
        if (handler == null) {
            throw new FlowRuntimeException("No runtime node handler registered for " + flowNodeType);
        }
        return handler;
    }
}
