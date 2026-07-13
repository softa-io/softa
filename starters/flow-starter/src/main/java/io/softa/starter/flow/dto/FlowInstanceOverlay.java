package io.softa.starter.flow.dto;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.starter.flow.enums.FlowNodeRunStatus;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.FlowExecutionTraceEntry;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;
import io.softa.starter.flow.runtime.state.FlowWaitToken;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.runtime.state.WaitType;

/**
 * Execution overlay for the canvas: per-node run state derived server-side from
 * the instance's state and trace, keyed by node id so the editor can paint
 * nodes without pairing trace events itself. {@code bundleId} identifies the
 * exact published graph the overlay belongs to.
 */
@Schema(name = "FlowInstanceOverlay")
public record FlowInstanceOverlay(

        @Schema(description = "Runtime instance id")
        String instanceId,

        @Schema(description = "Exact FlowBundle.id the instance runs on — fetch its design snapshot to render the graph")
        Long bundleId,

        @Schema(description = "Source FlowDesign.id")
        Long designId,

        @Schema(description = "Published revision")
        Integer revision,

        @Schema(description = "Instance status")
        FlowExecutionStatus status,

        @Schema(description = "Instance-level error message when Failed")
        String errorMessage,

        @Schema(description = "Per-node run state keyed by node id; untouched nodes are absent")
        Map<String, NodeState> nodeStates
) {

    @Schema(name = "FlowInstanceOverlayNodeState")
    public record NodeState(

            @Schema(description = "Run state to paint on the node")
            FlowNodeRunStatus status,

            @Schema(description = "First ENTER_NODE timestamp")
            LocalDateTime enteredAt,

            @Schema(description = "COMPLETE_NODE timestamp; null while open")
            LocalDateTime completedAt,

            @Schema(description = "Node-level error when Failed")
            String error,

            @Schema(description = "Pending approval details when WaitingApproval")
            PendingApproval approval
    ) {
    }

    public static FlowInstanceOverlay of(FlowExecutionState state) {
        Map<String, LocalDateTime> enteredAt = new LinkedHashMap<>();
        Map<String, LocalDateTime> completedAt = new LinkedHashMap<>();
        for (FlowExecutionTraceEntry entry : orEmpty(state.getTrace())) {
            if (entry.getNodeId() == null) {
                continue;
            }
            if (entry.getEventType() == FlowTraceEventType.ENTER_NODE) {
                enteredAt.putIfAbsent(entry.getNodeId(), entry.getEventTime());
            } else if (entry.getEventType() == FlowTraceEventType.COMPLETE_NODE) {
                completedAt.put(entry.getNodeId(), entry.getEventTime());
            }
        }

        Map<String, NodeState> nodes = new LinkedHashMap<>();
        for (String nodeId : orEmpty(state.getCompletedNodeIds())) {
            nodes.put(nodeId, new NodeState(FlowNodeRunStatus.COMPLETED,
                    enteredAt.get(nodeId), completedAt.get(nodeId), null, null));
        }
        for (PendingApproval approval : orEmpty(state.getPendingApprovals())) {
            nodes.put(approval.getNodeId(), new NodeState(FlowNodeRunStatus.WAITING_APPROVAL,
                    enteredAt.get(approval.getNodeId()), null, null, approval));
        }
        for (FlowWaitToken token : orEmpty(state.getWaitTokens())) {
            FlowNodeRunStatus waiting = token.getType() == WaitType.ASYNC
                    ? FlowNodeRunStatus.WAITING_ASYNC : FlowNodeRunStatus.WAITING_TIMER;
            nodes.put(token.getNodeId(), new NodeState(waiting,
                    enteredAt.get(token.getNodeId()), null, null, null));
        }
        if (state.getFailedNodeId() != null) {
            nodes.put(state.getFailedNodeId(), new NodeState(FlowNodeRunStatus.FAILED,
                    enteredAt.get(state.getFailedNodeId()), null, state.getErrorMessage(), null));
        }

        return new FlowInstanceOverlay(state.getInstanceId(), state.getBundleId(), state.getDesignId(),
                state.getFlowRevision(), state.getStatus(), state.getErrorMessage(), nodes);
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
