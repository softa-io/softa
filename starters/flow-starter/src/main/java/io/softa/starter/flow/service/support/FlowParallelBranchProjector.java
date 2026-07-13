package io.softa.starter.flow.service.support;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.entity.FlowExecutionTrace;
import io.softa.starter.flow.entity.FlowParallelBranch;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowTransition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;
import io.softa.starter.flow.service.FlowExecutionTraceService;

/**
 * Projects parallel branch execution records from an instance's fork/join trace rows.
 * <p>
 * For each PARALLEL_FORK trace row, looks up the compiled definition to find
 * the fork node's outgoing edges (branches) and the matching PARALLEL_JOIN node,
 * then builds a {@link FlowParallelBranch} record per branch edge. Fork/join rows
 * are read from {@code flow_execution_trace} (filtered to the two event types), not
 * from the in-memory state: the state's trace only holds the current attempt, and a
 * join often lands in a later attempt than its fork.
 * </p>
 */
@Component
public class FlowParallelBranchProjector {

    private static final List<FlowTraceEventType> FORK_JOIN_EVENTS =
            List.of(FlowTraceEventType.FORK_PARALLEL, FlowTraceEventType.JOIN_PARALLEL);

    private final FlowBundleRegistry bundleRegistry;
    private final FlowExecutionTraceService traceService;

    public FlowParallelBranchProjector(FlowBundleRegistry bundleRegistry,
                                       FlowExecutionTraceService traceService) {
        this.bundleRegistry = bundleRegistry;
        this.traceService = traceService;
    }

    public List<FlowParallelBranch> project(FlowExecutionState state) {
        if (state == null || state.getInstanceId() == null || state.getBundleId() == null) {
            return List.of();
        }
        Optional<CompiledFlowDefinition> defOpt = bundleRegistry.getByBundleId(state.getBundleId());
        if (defOpt.isEmpty()) {
            return List.of();
        }
        CompiledFlowDefinition definition = defOpt.get();

        List<FlowExecutionTrace> forkJoinRows =
                traceService.findByInstanceIdAndEventTypes(state.getInstanceId(), FORK_JOIN_EVENTS);

        // Keyed by nodeId, first occurrence wins
        Map<String, FlowExecutionTrace> forkTraceByNodeId = forkJoinRows.stream()
                .filter(e -> FlowTraceEventType.FORK_PARALLEL.equals(e.getEventType()))
                .collect(Collectors.toMap(FlowExecutionTrace::getNodeId, e -> e, (a, b) -> a));

        Map<String, FlowExecutionTrace> joinTraceByNodeId = forkJoinRows.stream()
                .filter(e -> FlowTraceEventType.JOIN_PARALLEL.equals(e.getEventType()))
                .collect(Collectors.toMap(FlowExecutionTrace::getNodeId, e -> e, (a, b) -> a));

        List<FlowParallelBranch> result = new ArrayList<>();
        for (Map.Entry<String, FlowExecutionTrace> entry : forkTraceByNodeId.entrySet()) {
            String forkNodeId = entry.getKey();
            FlowExecutionTrace forkEntry = entry.getValue();
            CompiledFlowNode forkNode = definition.getNodeIndex().get(forkNodeId);
            if (forkNode == null || forkNode.getOutgoingEdgeIds() == null) {
                continue;
            }

            // Find corresponding JOIN node via BFS
            String joinNodeId = findJoinNodeId(definition, forkNode);

            // Determine join trace entry, endTime, and status
            FlowExecutionTrace joinEntry = joinNodeId != null ? joinTraceByNodeId.get(joinNodeId) : null;
            FlowExecutionStatus branchStatus = resolveStatus(state, joinEntry);
            LocalDateTime endTime = joinEntry != null ? joinEntry.getEventTime() : null;

            // Create one record per branch edge
            for (String edgeId : forkNode.getOutgoingEdgeIds()) {
                CompiledFlowTransition transition = definition.getTransitionIndex().get(edgeId);
                if (transition == null) {
                    continue;
                }
                FlowParallelBranch branch = new FlowParallelBranch();
                branch.setInstanceId(state.getInstanceId());
                branch.setForkNodeId(forkNodeId);
                branch.setBranchNodeId(transition.getTarget());
                branch.setBranchName(resolveBranchName(definition, transition.getTarget()));
                branch.setStatus(branchStatus);
                branch.setStartTime(forkEntry.getEventTime());
                branch.setEndTime(endTime);
                if (forkEntry.getEventTime() != null && endTime != null) {
                    branch.setDurationMs(Duration.between(forkEntry.getEventTime(), endTime).toMillis());
                }
                if (FlowExecutionStatus.FAILED.equals(branchStatus)) {
                    branch.setErrorMessage(state.getErrorMessage());
                }
                result.add(branch);
            }
        }
        return result;
    }

    /**
     * BFS from fork node's branch targets to find the first PARALLEL_JOIN node.
     */
    private String findJoinNodeId(CompiledFlowDefinition def, CompiledFlowNode forkNode) {
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        // Seed: all branch target node IDs from the fork's outgoing edges
        for (String edgeId : forkNode.getOutgoingEdgeIds()) {
            CompiledFlowTransition t = def.getTransitionIndex().get(edgeId);
            if (t != null && t.getTarget() != null && visited.add(t.getTarget())) {
                queue.add(t.getTarget());
            }
        }

        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            CompiledFlowNode node = def.getNodeIndex().get(nodeId);
            if (node == null) {
                continue;
            }
            if (FlowNodeType.PARALLEL_JOIN.equals(node.getType())) {
                return nodeId;
            }
            if (node.getOutgoingEdgeIds() != null) {
                for (String edgeId : node.getOutgoingEdgeIds()) {
                    CompiledFlowTransition t = def.getTransitionIndex().get(edgeId);
                    if (t != null && t.getTarget() != null && visited.add(t.getTarget())) {
                        queue.add(t.getTarget());
                    }
                }
            }
        }
        return null;
    }

    private FlowExecutionStatus resolveStatus(FlowExecutionState state, FlowExecutionTrace joinEntry) {
        if (joinEntry != null) {
            return FlowExecutionStatus.COMPLETED;
        }
        if (FlowExecutionStatus.FAILED.equals(state.getStatus())) {
            return FlowExecutionStatus.FAILED;
        }
        return FlowExecutionStatus.RUNNING;
    }

    private String resolveBranchName(CompiledFlowDefinition def, String nodeId) {
        CompiledFlowNode node = def.getNodeIndex().get(nodeId);
        return node != null ? node.getLabel() : null;
    }
}
