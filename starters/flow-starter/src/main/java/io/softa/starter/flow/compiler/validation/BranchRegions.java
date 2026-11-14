package io.softa.starter.flow.compiler.validation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.enums.FlowNodeType;

/**
 * Where the branches leaving a node go by themselves, and how an inclusive gateway's branches meet: the graph reading
 * the gateway checks ({@link GatewayValidator}) and the compiler share.
 *
 * <p>A branch is followed from its first edge to the parallel joins where it meets other branches. A join all of whose
 * incoming edges come from within the branch — a fork and its join, or an inclusive gateway and its join, nested in
 * it — is passed: the branch goes on after it by itself. An end (End, Return Value) ends the path.
 */
public final class BranchRegions {

    private BranchRegions() {
    }

    /**
     * What a branch reaches by itself.
     *
     * @param nodes       the nodes it runs, the joins nested in it included, the ends left out
     * @param joinEntries the joins where it meets other branches, each with the edges it enters by
     * @param reachesEnd  whether a path of it ends at an End or a Return Value
     */
    record Branch(Set<String> nodes, Map<String, List<String>> joinEntries, boolean reachesEnd) {
    }

    /**
     * Follows a branch from its first edge. With {@code forksOpaque}, a parallel fork nested in it is not followed —
     * its branches may enter a join together, which reading it as one branch would take for alternatives.
     */
    static Branch walk(String firstEdgeId, FlowGraphContext context, boolean forksOpaque) {
        Set<String> nodes = new LinkedHashSet<>();
        Map<String, List<String>> held = new LinkedHashMap<>();
        Set<String> followed = new HashSet<>();
        boolean reachesEnd = false;
        Deque<String> queue = new ArrayDeque<>(List.of(firstEdgeId));
        while (!queue.isEmpty()) {
            while (!queue.isEmpty()) {
                FlowGraphEdge edge = context.edgeMap().get(queue.removeFirst());
                if (edge == null || edge.getTarget() == null || !followed.add(edge.getId())) {
                    continue;
                }
                FlowGraphNode target = context.nodeMap().get(edge.getTarget());
                if (target == null || nodes.contains(target.getId())) {
                    continue;
                }
                if (FlowNodeType.PARALLEL_JOIN.equals(target.getType())) {
                    held.computeIfAbsent(target.getId(), join -> new ArrayList<>()).add(edge.getId());
                } else if (isEnd(target)) {
                    reachesEnd = true;
                } else if (!forksOpaque || !FlowNodeType.PARALLEL_FORK.equals(target.getType())) {
                    nodes.add(target.getId());
                    queue.addAll(context.outgoingEdgeIds().getOrDefault(target.getId(), List.of()));
                }
            }
            // A join every incoming edge of which the branch brought is its own: pass it.
            for (Iterator<Map.Entry<String, List<String>>> joins = held.entrySet().iterator(); joins.hasNext(); ) {
                Map.Entry<String, List<String>> join = joins.next();
                List<String> incoming = context.incomingEdgeIds().getOrDefault(join.getKey(), List.of());
                if (!incoming.isEmpty() && join.getValue().containsAll(incoming)) {
                    joins.remove();
                    nodes.add(join.getKey());
                    queue.addAll(context.outgoingEdgeIds().getOrDefault(join.getKey(), List.of()));
                }
            }
        }
        return new Branch(nodes, held, reachesEnd);
    }

    /**
     * How an inclusive gateway's branches meet.
     *
     * @param joinId  the join its branches meet at, when they do and are paired with it; null otherwise
     * @param problem why they meet wrongly, or null
     */
    public record Pairing(String joinId, String problem) {

        static final Pairing NONE = new Pairing(null, null);

        /** Whether the branches meet at a join, paired with it. */
        public boolean paired() {
            return joinId != null;
        }
    }

    /**
     * Pairs an inclusive gateway with the join its branches meet at. Branches that each end on their own meet nowhere:
     * {@link Pairing#NONE}. Branches that meet must all meet at one join, and nowhere else: every branch — the default
     * included — goes on to the same join and enters it by one edge, never ends or reaches another join first, shares
     * no node with another branch, and no edge from outside enters it; the join's incoming edges are exactly theirs.
     * Then a branch the gateway does not take can be counted as arrived, which the engine does.
     */
    public static Pairing pairOf(FlowGraphNode gateway, FlowGraphContext context) {
        List<FlowGraphEdge> branches = outgoingEdges(gateway.getId(), context);
        List<Branch> walks = branches.stream().map(branch -> walk(branch.getId(), context, false)).toList();
        String join = walks.stream()
                .flatMap(walk -> walk.joinEntries().keySet().stream())
                .findFirst()
                .orElse(null);
        if (join == null) {
            return Pairing.NONE;
        }
        for (int i = 0; i < branches.size(); i++) {
            String problem = problemOf(branches.get(i), walks.get(i), join, context);
            if (problem != null) {
                return new Pairing(null, problem);
            }
        }
        for (int i = 0; i < branches.size(); i++) {
            for (int j = i + 1; j < branches.size(); j++) {
                Set<String> shared = new LinkedHashSet<>(walks.get(i).nodes());
                shared.retainAll(walks.get(j).nodes());
                if (!shared.isEmpty()) {
                    return new Pairing(null, "Branches '" + context.edgeName(branches.get(i).getId()) + "' and '"
                            + context.edgeName(branches.get(j).getId()) + "' meet at "
                            + quoted(shared, context::nodeName) + " before join '" + context.nodeName(join) + "'");
                }
            }
        }
        for (int i = 0; i < branches.size(); i++) {
            for (String nodeId : walks.get(i).nodes()) {
                for (String edgeId : context.incomingEdgeIds().getOrDefault(nodeId, List.of())) {
                    FlowGraphEdge edge = context.edgeMap().get(edgeId);
                    boolean inside = edge != null && (edgeId.equals(branches.get(i).getId())
                            || walks.get(i).nodes().contains(edge.getSource()));
                    if (!inside) {
                        return new Pairing(null, "Edge '" + context.edgeName(edgeId) + "' enters branch '"
                                + context.edgeName(branches.get(i).getId())
                                + "' from outside the gateway's branches, at '" + context.nodeName(nodeId) + "'");
                    }
                }
            }
        }
        Set<String> entries = walks.stream()
                .flatMap(walk -> walk.joinEntries().get(join).stream())
                .collect(Collectors.toSet());
        Set<String> incoming = new LinkedHashSet<>(context.incomingEdgeIds().getOrDefault(join, List.of()));
        incoming.removeAll(entries);
        if (!incoming.isEmpty()) {
            return new Pairing(null, "Join '" + context.nodeName(join) + "' is also entered from outside the gateway's "
                    + "branches, by " + quoted(incoming, context::edgeName));
        }
        return new Pairing(join, null);
    }

    /** Why one branch of a gateway whose branches meet at {@code join} goes wrong, or null. */
    private static String problemOf(FlowGraphEdge branch, Branch walk, String join, FlowGraphContext context) {
        String name = "Branch '" + context.edgeName(branch.getId()) + "'";
        String joinName = "join '" + context.nodeName(join) + "'";
        if (walk.reachesEnd()) {
            return name + " may end before " + joinName;
        }
        Set<String> others = new LinkedHashSet<>(walk.joinEntries().keySet());
        others.remove(join);
        if (!others.isEmpty()) {
            return name + " reaches join " + quoted(others, context::nodeName) + " instead of " + joinName;
        }
        List<String> entries = walk.joinEntries().get(join);
        if (entries == null) {
            return name + " does not reach " + joinName;
        }
        if (entries.size() > 1) {
            return name + " enters " + joinName + " by " + entries.size() + " edges ("
                    + quoted(entries, context::edgeName) + ")";
        }
        return null;
    }

    static List<FlowGraphEdge> outgoingEdges(String nodeId, FlowGraphContext context) {
        List<FlowGraphEdge> edges = new ArrayList<>();
        for (String edgeId : context.outgoingEdgeIds().getOrDefault(nodeId, List.of())) {
            FlowGraphEdge edge = context.edgeMap().get(edgeId);
            if (edge != null) {
                edges.add(edge);
            }
        }
        return edges;
    }

    /** Where a path ends: an End, or a Return Value, which ends the flow. */
    static boolean isEnd(FlowGraphNode node) {
        return FlowNodeType.END.equals(node.getType()) || FlowNodeType.RETURN_VALUE.equals(node.getType());
    }

    /** The ids by name, each quoted: {@code 'Manager', 'Finance'}. */
    static String quoted(Collection<String> ids, Function<String, String> name) {
        return ids.stream().map(id -> "'" + name.apply(id) + "'").collect(Collectors.joining(", "));
    }
}
