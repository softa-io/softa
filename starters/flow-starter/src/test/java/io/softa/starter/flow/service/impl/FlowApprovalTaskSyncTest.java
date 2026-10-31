package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.query.TaskInstanceContextEnricher;
import io.softa.starter.flow.service.support.FlowApprovalTaskProjector;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * The task projection kept in step with an instance after each action: one row per task, updated in place — a CC
 * no longer copied on every later action, its reading marked on it; a vote no longer copying the rows of those who
 * voted before; and an approver's own decision kept on their task when the next step approves itself at once.
 */
class FlowApprovalTaskSyncTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 30, 15, 38, 49);

    /** The rows as the table holds them: an empty actor list reads back as null. */
    private final List<FlowApprovalTask> stored = new ArrayList<>();
    private final List<FlowApprovalTask> created = new ArrayList<>();
    private final List<FlowApprovalTask> updated = new ArrayList<>();
    private long nextId = 1;
    private FlowApprovalTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        ApprovalAuditReader reader = new ApprovalAuditReader(new NoopApprovalActionLedger());
        service = spy(new FlowApprovalTaskServiceImpl(new FlowApprovalTaskProjector(reader),
                mock(FlowInstanceAccessGuard.class), reader,
                new TaskInstanceContextEnricher(mock(FlowInstanceService.class), mock(FlowUserNames.class),
                        mock(FlowBundleRegistry.class))));
        doAnswer(invocation -> List.copyOf(stored)).when(service).searchList(any(Filters.class));
        doAnswer(invocation -> {
            List<FlowApprovalTask> rows = invocation.getArgument(0);
            created.addAll(rows);
            rows.forEach(row -> stored.add(asStored(row)));
            return rows.stream().map(FlowApprovalTask::getId).toList();
        }).when(service).createList(anyList());
        doAnswer(invocation -> {
            updated.addAll(invocation.getArgument(0));
            return true;
        }).when(service).updateList(anyList());
    }

    @Test
    void aCcIsOneRowWhateverHappensAfterItAndItsReadingIsMarkedOnIt() {
        FlowExecutionState state = waitingOn(step(List.of("mgr")));
        state.getApprovalAuditDelta().add(entry(ApprovalActionType.CC, "mgr", "cc-1", "知悉"));
        service.onStateChanged(state);

        // Later actions on the instance: a comment, another CC.
        state.getApprovalAuditDelta().add(entry(ApprovalActionType.COMMENT, "mgr", null, "looked at it"));
        service.onStateChanged(state);
        state.getApprovalAuditDelta().add(entry(ApprovalActionType.CC, "mgr", "hrd-1", "for HR"));
        service.onStateChanged(state);

        assertEquals(1, ccRows("cc-1").size(), "one copy for cc-1, however many actions follow");
        assertEquals(FlowApprovalTaskStatus.PENDING, ccRows("cc-1").getFirst().getStatus());

        // cc-1 marks it read.
        state.getApprovalAuditDelta().add(entry(ApprovalActionType.READ, "cc-1", "mgr", null));
        service.onStateChanged(state);

        assertEquals(1, ccRows("cc-1").size(), "reading it adds no row");
        assertEquals(FlowApprovalTaskStatus.READ, ccRows("cc-1").getFirst().getStatus());
        assertEquals("知悉", ccRows("cc-1").getFirst().getComment(), "the CC's own message stays");
        assertEquals(1, ccRows("hrd-1").size());
        assertEquals(FlowApprovalTaskStatus.PENDING, ccRows("hrd-1").getFirst().getStatus(), "hrd-1 has not read theirs");
    }

    @Test
    void anotherApproversVoteDoesNotCopyTheRowOfWhoeverVotedBefore() {
        PendingApproval unanimous = step(List.of("a", "b", "c"));
        unanimous.setApprovalMode(VoteThresholdMode.UNANIMOUS);
        FlowExecutionState state = waitingOn(unanimous);
        service.onStateChanged(state);

        unanimous.setApprovedActors(List.of("a"));
        state.getApprovalAuditDelta().add(entry(ApprovalActionType.APPROVE, "a", null, "fine by me"));
        service.onStateChanged(state);
        unanimous.setApprovedActors(List.of("a", "b"));
        state.getApprovalAuditDelta().add(entry(ApprovalActionType.APPROVE, "b", null, "agreed"));
        service.onStateChanged(state);

        List<FlowApprovalTask> rowsOfA = stored.stream().filter(task -> "a".equals(task.getActorId())).toList();
        assertEquals(1, rowsOfA.size(), "a's task, not a copy per later vote");
        assertEquals(FlowApprovalTaskStatus.APPROVED, rowsOfA.getFirst().getStatus());
    }

    @Test
    void anApproversOwnApprovalStaysOnTheirTaskWhenTheNextStepApprovesItselfAtOnce() {
        FlowExecutionState state = waitingOn(step(List.of("mgr")));
        service.onStateChanged(state);

        // mgr approves; the second step resolves to mgr again and passes on the approver dedup, ending the approval.
        state.setPendingApprovals(new ArrayList<>());
        state.setStatus(FlowExecutionStatus.COMPLETED);
        state.getApprovalAuditDelta().add(entry(ApprovalActionType.APPROVE, "mgr", null, "R1 INF-09 approve after unfreeze"));
        ApprovalActionAuditEntry dedup = entry(ApprovalActionType.AUTO_APPROVE, "mgr", null,
                "Auto-approved (approver dedup: GLOBAL)");
        dedup.setNodeId("manager_l2");
        state.getApprovalAuditDelta().add(dedup);
        service.onStateChanged(state);

        FlowApprovalTask task = stored.stream().filter(row -> "manager".equals(row.getNodeId())).findFirst().orElseThrow();
        assertEquals(FlowApprovalTaskStatus.APPROVED, task.getStatus());
        assertEquals(ApprovalActionType.APPROVE, task.getAction());
        assertEquals("R1 INF-09 approve after unfreeze", task.getComment());
        assertEquals("mgr", task.getClosedByActorId());
    }

    @Test
    void anApproversOwnApprovalStaysOnTheirTaskWhenTheNextStepHasNobody() {
        FlowExecutionState state = waitingOn(step(List.of("mgr")));
        service.onStateChanged(state);

        state.setPendingApprovals(new ArrayList<>());
        state.setStatus(FlowExecutionStatus.COMPLETED);
        state.getApprovalAuditDelta().add(entry(ApprovalActionType.APPROVE, "mgr", null, "R1 INF-02 mgr approve"));
        ApprovalActionAuditEntry nobody = entry(ApprovalActionType.AUTO_APPROVE, null, null, "Auto-approved (no approver)");
        nobody.setNodeId("manager_l2");
        state.getApprovalAuditDelta().add(nobody);
        service.onStateChanged(state);

        FlowApprovalTask task = stored.stream().filter(row -> "manager".equals(row.getNodeId())).findFirst().orElseThrow();
        assertEquals(FlowApprovalTaskStatus.APPROVED, task.getStatus());
        assertEquals("R1 INF-02 mgr approve", task.getComment());
    }

    @Test
    void anotherApproverClosingTheStepStillCancelsTheTask() {
        PendingApproval anyOne = step(List.of("mgr", "deputy"));
        FlowExecutionState state = waitingOn(anyOne);
        service.onStateChanged(state);

        state.setPendingApprovals(new ArrayList<>());
        state.setStatus(FlowExecutionStatus.COMPLETED);
        state.getApprovalAuditDelta().add(entry(ApprovalActionType.APPROVE, "deputy", null, "done"));
        service.onStateChanged(state);

        FlowApprovalTask mgrs = stored.stream().filter(row -> "mgr".equals(row.getActorId())).findFirst().orElseThrow();
        assertEquals(FlowApprovalTaskStatus.CANCELED, mgrs.getStatus(), "closed by someone else's vote, not theirs");
    }

    private List<FlowApprovalTask> ccRows(String recipient) {
        return stored.stream()
                .filter(task -> FlowApprovalTaskType.CC.equals(task.getTaskType()) && recipient.equals(task.getActorId()))
                .toList();
    }

    private static FlowExecutionState waitingOn(PendingApproval step) {
        return FlowExecutionState.builder()
                .instanceId("i1")
                .flowId("transfer")
                .status(FlowExecutionStatus.WAITING)
                .pendingApprovals(new ArrayList<>(List.of(step)))
                .approvalAuditDelta(new ArrayList<>())
                .build();
    }

    private static PendingApproval step(List<String> approvers) {
        return PendingApproval.builder()
                .nodeId("manager")
                .nodeLabel("Direct Manager")
                .cycleNumber(1)
                .approvers(new ArrayList<>(approvers))
                .approvalMode(VoteThresholdMode.ANY_ONE)
                .build();
    }

    private static ApprovalActionAuditEntry entry(ApprovalActionType action, String actorId, String targetActorId,
                                                  String comment) {
        return ApprovalActionAuditEntry.builder()
                .action(action)
                .eventTime(T0)
                .nodeId("manager")
                .nodeLabel("Direct Manager")
                .cycleNumber(1)
                .actorId(actorId)
                .targetActorId(targetActorId)
                .comment(comment)
                .build();
    }

    /** The row as the table gives it back: an id, and an empty actor list read back as null. */
    private FlowApprovalTask asStored(FlowApprovalTask row) {
        row.setId(nextId++);
        if (row.getApprovedActors() != null && row.getApprovedActors().isEmpty()) {
            row.setApprovedActors(null);
        }
        if (row.getRejectedActors() != null && row.getRejectedActors().isEmpty()) {
            row.setRejectedActors(null);
        }
        return row;
    }
}
