package io.softa.starter.flow.compiler;

import java.util.List;
import java.util.Map;

import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;

/**
 * Shared graph context built once and passed to validators and transformers.
 */
public record FlowGraphContext(
        Map<String, FlowGraphNode> nodeMap,
        Map<String, FlowGraphEdge> edgeMap,
        Map<String, List<String>> incomingEdgeIds,
        Map<String, List<String>> outgoingEdgeIds,
        List<String> entryNodeIds,
        List<String> terminalNodeIds
) {}
