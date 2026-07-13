package io.softa.starter.flow.compiler.graph;

import java.util.*;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;

/**
 * Kahn-based topological sort with cycle detection.
 * <p>
 * Cycles are reported as one anchored {@link CompileDiagnostic} per unsortable node
 * (a node on a cycle, or reachable only through one) so the editor can highlight
 * the offending region instead of showing a single flow-level message.
 */
public final class TopologicalSorter {

    public static final String GRAPH_CYCLE = "GRAPH_CYCLE";

    private TopologicalSorter() {}

    public static List<String> topologicalSort(Collection<FlowGraphNode> nodes,
                                               Collection<FlowGraphEdge> edges,
                                               List<CompileDiagnostic> diagnostics) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoingNodes = new HashMap<>();
        Map<String, FlowGraphNode> nodeById = new HashMap<>();
        for (FlowGraphNode node : nodes) {
            indegree.put(node.getId(), 0);
            nodeById.put(node.getId(), node);
        }
        for (FlowGraphEdge edge : edges) {
            indegree.compute(edge.getTarget(), (_, value) -> value == null ? 1 : value + 1);
            outgoingNodes.computeIfAbsent(edge.getSource(), _ -> new ArrayList<>()).add(edge.getTarget());
        }

        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((nodeId, value) -> {
            if (value == 0) {
                queue.add(nodeId);
            }
        });

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String nodeId = queue.remove();
            sorted.add(nodeId);
            for (String nextNodeId : outgoingNodes.getOrDefault(nodeId, Collections.emptyList())) {
                int updated = indegree.computeIfPresent(nextNodeId, (_, value) -> value - 1);
                if (updated == 0) {
                    queue.add(nextNodeId);
                }
            }
        }

        if (sorted.size() != nodes.size()) {
            Set<String> sortedIds = new HashSet<>(sorted);
            for (FlowGraphNode node : nodes) {
                if (!sortedIds.contains(node.getId())) {
                    FlowGraphNode unsortable = nodeById.get(node.getId());
                    diagnostics.add(CompileDiagnostic.nodeLevel(node.getId(),
                            unsortable.getType() != null ? unsortable.getType().getType() : null,
                            GRAPH_CYCLE,
                            "Node '" + node.getId() + "' is part of a cycle, or is only reachable through one"));
                }
            }
        }
        return sorted;
    }
}
