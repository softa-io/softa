package io.softa.starter.flow.service.support;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlowApprovalTaskProjectorTest {

    private final FlowBundleRegistry bundleRegistry = mock(FlowBundleRegistry.class);
    private final ModelService<?> modelService = mock(ModelService.class);
    private final FlowApprovalTaskProjector projector = new FlowApprovalTaskProjector(
            new ApprovalAuditReader(new NoopApprovalActionLedger()), bundleRegistry, modelService);

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

    @Test
    void shouldSnapshotConfiguredFormFieldsFromBusinessRow() {
        ApprovalNodeConfig config = new ApprovalNodeConfig();
        config.setFormPermissions(Map.of(
                "claimName", FormFieldPermission.READONLY,
                "totalAmount", FormFieldPermission.READONLY,
                "salary", FormFieldPermission.HIDDEN));
        CompiledFlowNode node = mock(CompiledFlowNode.class);
        when(node.getParsedConfig()).thenReturn(config);
        CompiledFlowDefinition definition = mock(CompiledFlowDefinition.class);
        when(definition.getNodeIndex()).thenReturn(Map.of("managerApproval", node));
        when(bundleRegistry.getByBundleId(7L)).thenReturn(Optional.of(definition));
        when(modelService.searchOne(eq("ExpenseClaim"), any())).thenReturn(Optional.of(Map.of(
                "claimName", "Shanghai trip",
                "totalAmount", "120.00",
                "salary", "secret")));

        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("instance-1")
                .bundleId(7L)
                .modelName("ExpenseClaim")
                .rowId("42")
                .pendingApprovals(List.of(PendingApproval.builder()
                        .nodeId("managerApproval")
                        .approvers(List.of("manager"))
                        .build()))
                .build();

        var task = projector.project(state).getFirst();

        assertNotNull(task.getFormSnapshot());
        assertTrue(task.getFormSnapshot().contains("Shanghai trip"));
        assertTrue(task.getFormSnapshot().contains("totalAmount"));
        // Hidden fields must never leak into the snapshot an approver can read.
        assertFalse(task.getFormSnapshot().contains("secret"));
    }

    @Test
    void shouldLeaveSnapshotNullWithoutFormConfig() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("instance-1")
                .pendingApprovals(List.of(PendingApproval.builder()
                        .nodeId("managerApproval")
                        .approvers(List.of("manager"))
                        .build()))
                .build();

        assertNull(projector.project(state).getFirst().getFormSnapshot());
    }
}
