package io.softa.starter.flow.compiler.transform;

import java.util.*;

import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowTransition;
import io.softa.starter.flow.runtime.bundle.NodeConfigParser;

/**
 * Transforms design-time nodes and edges into compiled runtime structures.
 */
public final class NodeTransformer {

    private NodeTransformer() {}

    public static Map<String, CompiledFlowNode> compileNodes(Collection<FlowGraphNode> nodes,
                                                              Map<String, List<String>> incomingEdgeIds,
                                                              Map<String, List<String>> outgoingEdgeIds) {
        Map<String, CompiledFlowNode> compiled = new LinkedHashMap<>();
        for (FlowGraphNode node : nodes) {
            Map<String, Object> config = node.getConfig();
            CompiledFlowNode compiledNode = CompiledFlowNode.builder()
                    .nodeId(node.getId())
                    .type(node.getType())
                    .label(node.getLabel())
                    .config(config)
                    .parsedConfig(NodeConfigParser.parse(node.getType(), config))
                    .incomingEdgeIds(defaultList(incomingEdgeIds.get(node.getId())))
                    .outgoingEdgeIds(defaultList(outgoingEdgeIds.get(node.getId())))
                    .build();
            compiled.put(node.getId(), compiledNode);
        }
        return compiled;
    }

    public static Map<String, CompiledFlowTransition> compileTransitions(Collection<FlowGraphEdge> edges) {
        Map<String, CompiledFlowTransition> compiled = new LinkedHashMap<>();
        for (FlowGraphEdge edge : edges) {
            compiled.put(edge.getId(), CompiledFlowTransition.builder()
                    .edgeId(edge.getId())
                    .source(edge.getSource())
                    .target(edge.getTarget())
                    .condition(edge.getCondition())
                    .conditionExpression(edge.getConditionExpression())
                    .build());
        }
        return compiled;
    }

    private static <T> List<T> defaultList(List<T> values) {
        return values == null ? Collections.emptyList() : List.copyOf(values);
    }
}
