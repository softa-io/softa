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
        List<String> entryNodeIds
) {

    /** What a message calls a node: its label, or its id when it has none or is not in the graph. */
    public String nodeName(String nodeId) {
        FlowGraphNode node = nodeId == null ? null : nodeMap.get(nodeId);
        return node == null ? nodeId : node.displayName();
    }

    /**
     * What a message calls an edge: its label, or else the nodes it joins by name — {@code Manager → Finance}. Edge
     * ids are generated and shown nowhere on the canvas; an edge not in the graph keeps its id.
     */
    public String edgeName(String edgeId) {
        FlowGraphEdge edge = edgeId == null ? null : edgeMap.get(edgeId);
        if (edge == null) {
            return edgeId;
        }
        if (edge.getLabel() != null && !edge.getLabel().isBlank()) {
            return edge.getLabel();
        }
        return nodeName(edge.getSource()) + " → " + nodeName(edge.getTarget());
    }
}
