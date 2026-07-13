package io.softa.starter.flow.compiler.transform;

import java.util.*;

import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.NodeErrorStrategy;
import io.softa.starter.flow.api.CompiledFlowCapabilitySummary;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowTransition;
import io.softa.starter.flow.runtime.bundle.NodeConfigParser;
import io.softa.starter.flow.runtime.nodeconfig.NodeErrorConfig;

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
                    .errorConfig(extractErrorConfig(config))
                    .parsedConfig(NodeConfigParser.parse(node.getType(), config))
                    .incomingEdgeIds(defaultList(incomingEdgeIds.get(node.getId())))
                    .outgoingEdgeIds(defaultList(outgoingEdgeIds.get(node.getId())))
                    .position(node.getPosition())
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
                    .sourceHandle(edge.getSourceHandle())
                    .target(edge.getTarget())
                    .targetHandle(edge.getTargetHandle())
                    .conditionExpression(edge.getConditionExpression())
                    .data(edge.getData())
                    .build());
        }
        return compiled;
    }

    public static CompiledFlowCapabilitySummary buildCapabilitySummary(Collection<FlowGraphNode> nodes) {
        return CompiledFlowCapabilitySummary.builder()
                .hasApproval(nodes.stream().anyMatch(n -> FlowNodeType.APPROVAL.equals(n.getType())))
                .hasSubflow(nodes.stream().anyMatch(n -> FlowNodeType.SUBFLOW.equals(n.getType())))
                .hasParallelGateway(nodes.stream().anyMatch(n ->
                        FlowNodeType.PARALLEL_FORK.equals(n.getType()) || FlowNodeType.PARALLEL_JOIN.equals(n.getType())))
                .hasLoop(nodes.stream().anyMatch(n -> FlowNodeType.FOR_EACH.equals(n.getType())))
                .build();
    }

    private static NodeErrorConfig extractErrorConfig(Map<String, Object> config) {
        if (config == null) {
            return NodeErrorConfig.failFast();
        }
        NodeErrorStrategy strategy = extractErrorStrategy(config);
        int retryCount = extractRetryCount(config);
        return NodeErrorConfig.builder()
                .strategy(strategy)
                .retryCount(retryCount)
                .build();
    }

    private static NodeErrorStrategy extractErrorStrategy(Map<String, Object> config) {
        Object raw = config.get("errorStrategy");
        if (raw instanceof NodeErrorStrategy strategy) {
            return strategy;
        }
        if (raw instanceof String s) {
            return NodeErrorStrategy.fromValue(s);
        }
        return NodeErrorStrategy.FAIL;
    }

    private static int extractRetryCount(Map<String, Object> config) {
        Object raw = config.get("retryCount");
        if (raw instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (raw instanceof String s) {
            try {
                return Math.max(0, Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }


    private static <T> List<T> defaultList(List<T> values) {
        return values == null ? Collections.emptyList() : List.copyOf(values);
    }
}
