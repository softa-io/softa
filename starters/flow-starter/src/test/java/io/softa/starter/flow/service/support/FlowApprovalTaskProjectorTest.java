package io.softa.starter.flow.service.support;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

import static org.junit.jupiter.api.Assertions.*;

class FlowApprovalTaskProjectorTest {

    private final FlowApprovalTaskProjector projector = new FlowApprovalTaskProjector(
            new ApprovalAuditReader(new NoopApprovalActionLedger()));

    @Test
    void shouldProjectPerActorTasksFromPendingApprovalProgress() {
        LocalDateTime approvedAt = LocalDateTime.now().minusMinutes(1);
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("instance-1")
                .pendingApprovals(List.of(PendingApproval.builder()
                        .flowCode("leave-flow")
                        .flowRevision(3)
                        .nodeId("managerApproval")
                        .nodeLabel("Manager Approval")
                        .cycleNumber(2)
                        .approvers(List.of("manager", "hr"))
                        .dynamicApprovers(true)
                        .approvalMode(VoteThresholdMode.UNANIMOUS)
                        .requiredApprovalCount(2)
                        .totalApproverCount(2)
                        .rejectMode(VoteThresholdMode.ANY_ONE)
                        .requiredRejectCount(1)
                        .approvedActors(List.of("manager"))
                        .rejectedActors(List.of())
                        .build()))
                .approvalAuditDelta(List.of(ApprovalActionAuditEntry.builder()
                        .action(ApprovalActionType.APPROVE)
                        .cycleNumber(2)
                        .nodeId("managerApproval")
                        .actorId("manager")
                        .comment("approved")
                        .eventTime(approvedAt)
                        .build()))
                .build();

        var tasks = projector.project(state);

        assertEquals(2, tasks.size());

        var managerTask = tasks.stream().filter(task -> "manager".equals(task.getActorId())).findFirst().orElseThrow();
        assertEquals(FlowApprovalTaskStatus.APPROVED, managerTask.getStatus());
        assertEquals(2, managerTask.getCycleNumber());
        assertEquals(ApprovalActionType.APPROVE, managerTask.getAction());
        assertEquals("approved", managerTask.getComment());
        assertEquals(List.of("manager"), managerTask.getApprovedActors());
        assertNotNull(managerTask.getEndTime());

        var hrTask = tasks.stream().filter(task -> "hr".equals(task.getActorId())).findFirst().orElseThrow();
        assertEquals(FlowApprovalTaskStatus.PENDING, hrTask.getStatus());
        assertNull(hrTask.getEndTime());
        assertEquals(List.of("manager", "hr"), hrTask.getCandidateActors());
        assertEquals(VoteThresholdMode.UNANIMOUS, hrTask.getApprovalMode());
        assertEquals(2, hrTask.getRequiredApprovalCount());
    }

    @Test
    void shouldMarkBlockedTaskWhenAddSignBeforePrerequisiteIsStillPending() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("instance-add-sign")
                .pendingApprovals(List.of(PendingApproval.builder()
                        .flowCode("leave-flow")
                        .flowRevision(3)
                        .nodeId("managerApproval")
                        .nodeLabel("Manager Approval")
                        .cycleNumber(1)
                        .approvers(List.of("lead", "manager"))
                        .approvalMode(VoteThresholdMode.ANY_ONE)
                        .requiredApprovalCount(1)
                        .totalApproverCount(2)
                        .rejectMode(VoteThresholdMode.ANY_ONE)
                        .requiredRejectCount(1)
                        .approvedActors(List.of())
                        .rejectedActors(List.of())
                        .blockedActorId("manager")
                        .prerequisiteActorId("lead")
                        .build()))
                .build();

        var tasks = projector.project(state);

        var blockedTask = tasks.stream().filter(task -> "manager".equals(task.getActorId())).findFirst().orElseThrow();
        assertTrue(Boolean.TRUE.equals(blockedTask.getBlocked()));
        assertEquals("lead", blockedTask.getBlockedByActorId());
        assertEquals(FlowApprovalTaskStatus.PENDING, blockedTask.getStatus());

        var prerequisiteTask = tasks.stream().filter(task -> "lead".equals(task.getActorId())).findFirst().orElseThrow();
        assertFalse(Boolean.TRUE.equals(prerequisiteTask.getBlocked()));
        assertNull(prerequisiteTask.getBlockedByActorId());
    }

    @Test
    void shouldMarkAddedFollowUpSignerBlockedWhenAddSignAfterSourceHasNotApprovedYet() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("instance-add-sign-after")
                .pendingApprovals(List.of(PendingApproval.builder()
                        .flowCode("leave-flow")
                        .flowRevision(3)
                        .nodeId("managerApproval")
                        .nodeLabel("Manager Approval")
                        .cycleNumber(1)
                        .approvers(List.of("manager", "reviewer"))
                        .approvalMode(VoteThresholdMode.ANY_ONE)
                        .requiredApprovalCount(1)
                        .totalApproverCount(2)
                        .rejectMode(VoteThresholdMode.ANY_ONE)
                        .requiredRejectCount(1)
                        .approvedActors(List.of())
                        .rejectedActors(List.of())
                        .blockedActorId("reviewer")
                        .prerequisiteActorId("manager")
                        .build()))
                .build();

        var tasks = projector.project(state);

        var managerTask = tasks.stream().filter(task -> "manager".equals(task.getActorId())).findFirst().orElseThrow();
        assertFalse(Boolean.TRUE.equals(managerTask.getBlocked()));
        assertNull(managerTask.getBlockedByActorId());

        var reviewerTask = tasks.stream().filter(task -> "reviewer".equals(task.getActorId())).findFirst().orElseThrow();
        assertTrue(Boolean.TRUE.equals(reviewerTask.getBlocked()));
        assertEquals("manager", reviewerTask.getBlockedByActorId());
        assertEquals(FlowApprovalTaskStatus.PENDING, reviewerTask.getStatus());
    }

    @Test
    void shouldProjectCcAuditEntriesIntoClosedCcTasks() {
        LocalDateTime ccAt = LocalDateTime.now();
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("instance-cc")
                .flowCode("leave-flow")
                .flowRevision(3)
                .pendingApprovals(List.of(PendingApproval.builder()
                        .flowCode("leave-flow")
                        .flowRevision(3)
                        .nodeId("managerApproval")
                        .nodeLabel("Manager Approval")
                        .cycleNumber(1)
                        .approvers(List.of("manager"))
                        .approvalMode(VoteThresholdMode.ANY_ONE)
                        .requiredApprovalCount(1)
                        .totalApproverCount(1)
                        .rejectMode(VoteThresholdMode.ANY_ONE)
                        .requiredRejectCount(1)
                        .approvedActors(List.of())
                        .rejectedActors(List.of())
                        .build()))
                .approvalAuditDelta(List.of(ApprovalActionAuditEntry.builder()
                        .action(ApprovalActionType.CC)
                        .nodeId("managerApproval")
                        .nodeLabel("Manager Approval")
                        .cycleNumber(1)
                        .actorId("manager")
                        .targetActorId("observer")
                        .comment("FYI")
                        .eventTime(ccAt)
                        .build()))
                .build();

        var tasks = projector.project(state);

        var ccTask = tasks.stream().filter(task -> "observer".equals(task.getActorId())).findFirst().orElseThrow();
        assertEquals(FlowApprovalTaskType.CC, ccTask.getTaskType());
        assertEquals(FlowApprovalTaskStatus.PENDING, ccTask.getStatus());
        assertEquals(ApprovalActionType.CC, ccTask.getAction());
        assertEquals("manager", ccTask.getClosedByActorId());
        assertEquals(ccAt, ccTask.getEndTime());
    }
}
