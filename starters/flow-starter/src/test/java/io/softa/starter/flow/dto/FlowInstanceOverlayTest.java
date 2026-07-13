package io.softa.starter.flow.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.enums.FlowNodeRunStatus;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.FlowExecutionTraceEntry;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;
import io.softa.starter.flow.runtime.state.PendingApproval;

import static org.junit.jupiter.api.Assertions.*;

class FlowInstanceOverlayTest {

    @Test
    void of_derivesPerNodeStatesFromStateAndTrace() {
        LocalDateTime t0 = LocalDateTime.of(2026, 7, 1, 10, 0);
        List<FlowExecutionTraceEntry> trace = new ArrayList<>(List.of(
                entry("start", FlowTraceEventType.ENTER_NODE, t0),
                entry("start", FlowTraceEventType.COMPLETE_NODE, t0.plusSeconds(1)),
                entry("approval1", FlowTraceEventType.ENTER_NODE, t0.plusSeconds(2))));

        PendingApproval pending = new PendingApproval();
        pending.setNodeId("approval1");

        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("inst-1")
                .bundleId(11L)
                .designId(22L)
                .flowRevision(3)
                .status(FlowExecutionStatus.WAITING)
                .completedNodeIds(new ArrayList<>(List.of("start")))
                .pendingApprovals(new ArrayList<>(List.of(pending)))
                .trace(trace)
                .build();

        FlowInstanceOverlay overlay = FlowInstanceOverlay.of(state);

        assertEquals(11L, overlay.bundleId());
        assertEquals(FlowNodeRunStatus.COMPLETED, overlay.nodeStates().get("start").status());
        assertEquals(t0, overlay.nodeStates().get("start").enteredAt());
        assertEquals(t0.plusSeconds(1), overlay.nodeStates().get("start").completedAt());
        FlowInstanceOverlay.NodeState approval = overlay.nodeStates().get("approval1");
        assertEquals(FlowNodeRunStatus.WAITING_APPROVAL, approval.status());
        assertSame(pending, approval.approval());
        assertNull(approval.completedAt());
    }

    @Test
    void of_marksFailedNodeWithError() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("inst-2")
                .status(FlowExecutionStatus.FAILED)
                .errorMessage("boom")
                .failedNodeId("task1")
                .completedNodeIds(List.of("start"))
                .trace(List.of())
                .build();

        FlowInstanceOverlay overlay = FlowInstanceOverlay.of(state);

        FlowInstanceOverlay.NodeState failed = overlay.nodeStates().get("task1");
        assertEquals(FlowNodeRunStatus.FAILED, failed.status());
        assertEquals("boom", failed.error());
        assertEquals(FlowNodeRunStatus.COMPLETED, overlay.nodeStates().get("start").status());
    }

    private static FlowExecutionTraceEntry entry(String nodeId, FlowTraceEventType type, LocalDateTime time) {
        return FlowExecutionTraceEntry.builder()
                .nodeId(nodeId)
                .flowNodeType(FlowNodeType.CALL_SERVICE)
                .eventType(type)
                .eventTime(time)
                .build();
    }
}
