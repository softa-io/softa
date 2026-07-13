package io.softa.starter.flow.compiler.graph;

import java.util.*;

import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.enums.FlowNodeType;

/**
 * Builds lookup indexes and resolves entry/terminal nodes from graph primitives.
 * <p>
 * Structural defects (blank/duplicate ids, missing types, dangling endpoints) are
 * reported as anchored {@link CompileDiagnostic}s into the caller's sink — all of
 * them, not fail-fast — so the editor can mark every offending node/edge on the
 * canvas in a single round trip. Defective entries are skipped from the indexes.
 */
public final class GraphIndexBuilder {

    public static final String MISSING_NODE_ID   = "MISSING_NODE_ID";
    public static final String MISSING_NODE_TYPE = "MISSING_NODE_TYPE";
    public static final String DUPLICATE_NODE_ID = "DUPLICATE_NODE_ID";
    public static final String MISSING_EDGE_ID       = "MISSING_EDGE_ID";
    public static final String MISSING_EDGE_ENDPOINT = "MISSING_EDGE_ENDPOINT";
    public static final String DUPLICATE_EDGE_ID     = "DUPLICATE_EDGE_ID";

    private GraphIndexBuilder() {}

    public static Map<String, FlowGraphNode> indexNodes(List<FlowGraphNode> nodes,
                                                        List<CompileDiagnostic> diagnostics) {
        Map<String, FlowGraphNode> nodeMap = new LinkedHashMap<>();
        for (FlowGraphNode node : nodes) {
            if (node == null || !StringUtils.hasText(node.getId())) {
                diagnostics.add(CompileDiagnostic.flowLevel(MISSING_NODE_ID,
                        "Every node must define a non-empty id"));
                continue;
            }
            if (node.getType() == null) {
                diagnostics.add(CompileDiagnostic.nodeLevel(node.getId(), null, MISSING_NODE_TYPE,
                        "Node '" + node.getId() + "' must define a type"));
                continue;
            }
            if (nodeMap.putIfAbsent(node.getId(), node) != null) {
                diagnostics.add(CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(),
                        DUPLICATE_NODE_ID, "Duplicate node id: " + node.getId()));
            }
        }
        return nodeMap;
    }

    public static Map<String, FlowGraphEdge> indexEdges(List<FlowGraphEdge> edges,
                                                        List<CompileDiagnostic> diagnostics) {
        Map<String, FlowGraphEdge> edgeMap = new LinkedHashMap<>();
        for (FlowGraphEdge edge : edges) {
            if (edge == null || !StringUtils.hasText(edge.getId())) {
                diagnostics.add(CompileDiagnostic.flowLevel(MISSING_EDGE_ID,
                        "Every edge must define a non-empty id"));
                continue;
            }
            if (!StringUtils.hasText(edge.getSource()) || !StringUtils.hasText(edge.getTarget())) {
                diagnostics.add(CompileDiagnostic.edgeLevel(edge.getId(), null, null, MISSING_EDGE_ENDPOINT,
                        "Edge '" + edge.getId() + "' must define source and target node ids"));
                continue;
            }
            if (edgeMap.putIfAbsent(edge.getId(), edge) != null) {
                diagnostics.add(CompileDiagnostic.edgeLevel(edge.getId(), null, null, DUPLICATE_EDGE_ID,
                        "Duplicate edge id: " + edge.getId()));
            }
        }
        return edgeMap;
    }

    public static Map<String, List<String>> buildIncomingEdgeIds(Collection<FlowGraphEdge> edges) {
        Map<String, List<String>> incoming = new HashMap<>();
        for (FlowGraphEdge edge : edges) {
            incoming.computeIfAbsent(edge.getTarget(), _ -> new ArrayList<>()).add(edge.getId());
        }
        return incoming;
    }

    public static Map<String, List<String>> buildOutgoingEdgeIds(Collection<FlowGraphEdge> edges) {
        Map<String, List<String>> outgoing = new HashMap<>();
        for (FlowGraphEdge edge : edges) {
            outgoing.computeIfAbsent(edge.getSource(), _ -> new ArrayList<>()).add(edge.getId());
        }
        return outgoing;
    }

    public static List<String> resolveEntryNodes(Collection<FlowGraphNode> nodes,
                                                  Map<String, List<String>> incomingEdgeIds) {
        List<String> explicitStarts = nodes.stream()
                .filter(node -> FlowNodeType.START.equals(node.getType()))
                .map(FlowGraphNode::getId)
                .toList();
        if (!explicitStarts.isEmpty()) {
            return explicitStarts;
        }
        return nodes.stream()
                .map(FlowGraphNode::getId)
                .filter(id -> CollectionUtils.isEmpty(incomingEdgeIds.get(id)))
                .toList();
    }

    public static List<String> resolveTerminalNodes(Collection<FlowGraphNode> nodes,
                                                     Map<String, List<String>> outgoingEdgeIds) {
        List<String> explicitEnds = nodes.stream()
                .filter(node -> FlowNodeType.END.equals(node.getType()))
                .map(FlowGraphNode::getId)
                .toList();
        if (!explicitEnds.isEmpty()) {
            return explicitEnds;
        }
        return nodes.stream()
                .map(FlowGraphNode::getId)
                .filter(id -> CollectionUtils.isEmpty(outgoingEdgeIds.get(id)))
                .toList();
    }
}
