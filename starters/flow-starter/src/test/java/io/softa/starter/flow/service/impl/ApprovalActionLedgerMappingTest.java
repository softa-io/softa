package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.entity.FlowApprovalRecord;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Ledger mapping fidelity: an audit entry survives the entry → row → entry round trip
 * for every persisted field. Threshold snapshots (approvalMode / required counts / …)
 * are intentionally not persisted and therefore not asserted.
 */
class ApprovalActionLedgerMappingTest {

    @Test
    void entrySurvivesRecordRoundTrip() {
        LocalDateTime eventTime = LocalDateTime.now().minusMinutes(2);
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("instance-42")
                .flowCode("leave-flow")
                .flowRevision(5)
                .build();
        ApprovalActionAuditEntry entry = ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.RETURN)
                .cycleNumber(2)
                .eventTime(eventTime)
                .flowCode("leave-flow")
                .flowRevision(5)
                .nodeId("managerApproval")
                .nodeLabel("Manager Approval")
                .actorId("manager")
                .comment("fix it")
                .statusBefore(FlowExecutionStatus.WAITING)
                .statusAfter(FlowExecutionStatus.RETURNED)
                .targetActorId("initiator-1")
                .approvedActors(List.of("manager"))
                .rejectedActors(List.of())
                .build();

        FlowApprovalRecord record = FlowApprovalRecordServiceImpl.toRecord(state, entry);
        record.setSequence(7); // assigned by the flush loop
        ApprovalActionAuditEntry restored = FlowApprovalRecordServiceImpl.toEntry(record);

        assertEquals("instance-42", record.getInstanceId());
        assertEquals(7, restored.getSequence());
        assertEquals(ApprovalActionType.RETURN, restored.getAction());
        assertEquals(eventTime, restored.getEventTime());
        assertEquals("leave-flow", restored.getFlowCode());
        assertEquals(5, restored.getFlowRevision());
        assertEquals("managerApproval", restored.getNodeId());
        assertEquals("Manager Approval", restored.getNodeLabel());
        assertEquals(2, restored.getCycleNumber());
        assertEquals("manager", restored.getActorId());
        assertEquals("initiator-1", restored.getTargetActorId());
        assertEquals("fix it", restored.getComment());
        assertEquals(FlowExecutionStatus.WAITING, restored.getStatusBefore());
        assertEquals(FlowExecutionStatus.RETURNED, restored.getStatusAfter());
        assertEquals(List.of("manager"), restored.getApprovedActors());
        assertEquals(List.of(), restored.getRejectedActors());
    }

    @Test
    void entryFlowIdentityFallsBackToState() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("instance-9")
                .flowCode("expense")
                .flowRevision(3)
                .build();
        ApprovalActionAuditEntry bare = ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.COMMENT)
                .build();

        FlowApprovalRecord record = FlowApprovalRecordServiceImpl.toRecord(state, bare);

        assertEquals("expense", record.getFlowCode());
        assertEquals(3, record.getFlowRevision());
        assertNull(record.getSequence());
    }
}
