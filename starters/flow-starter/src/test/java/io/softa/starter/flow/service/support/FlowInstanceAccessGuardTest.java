package io.softa.starter.flow.service.support;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.MockedStatic;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.runtime.store.ApprovalActionLedger;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard.RecordAccess;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard.RecordAdmission;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FlowInstanceAccessGuard}: the initiator and the instance's task holders see it
 * by default; anyone else through their own permissions — on the instance row, or on the record a
 * record approval approves. Reading the record through the instance past one's own permissions stays
 * with the default grants; a reader of the record reads it through their own. Read by the record, it is
 * lent by the newest of its approvals the caller took part in. Whom the instance no longer names — the
 * approver a termination cleared — its tasks still do, asked only then; an administrator's urge, termination
 * or reassignment makes nobody a participant.
 */
class FlowInstanceAccessGuardTest {

    private final FlowInstanceService instanceService = mock(FlowInstanceService.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final FlowInstanceAccessGuard guard = new FlowInstanceAccessGuard(instanceService,
            new ApprovalAuditReader(new NoopApprovalActionLedger()), modelService);

    private FlowInstance instanceInitiatedBy(String initiatorId) {
        FlowInstance instance = new FlowInstance();
        instance.setInitiatorId(initiatorId);
        return instance;
    }

    @Test
    void participantInResultIsAllowed() {
        // A caller who appears in the loaded rows is allowed without touching the instance store.
        assertDoesNotThrow(() -> guard.requireInstanceViewer("i1", "u1", true));
    }

    @Test
    void initiatorIsAllowedEvenWhenNotInResult() {
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.of(instanceInitiatedBy("u1")));
        assertDoesNotThrow(() -> guard.requireInstanceViewer("i1", "u1", false));
    }

    @Test
    void nonParticipantNonInitiatorIsDenied() {
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.of(instanceInitiatedBy("someoneElse")));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer("i1", "u1", false));
    }

    @Test
    void missingInstanceIsDenied() {
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.empty());
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer("i1", "u1", false));
    }

    @Test
    void blankRequesterIsDenied() {
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer("i1", "", false));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer("i1", null, false));
    }

    @Test
    void runtimeStateInitiatorIsAllowed() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("u1")
                .build();

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "u1"));
    }

    @Test
    void runtimeStatePendingApproverIsAllowed() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("initiator")
                .pendingApprovals(List.of(PendingApproval.builder()
                        .approvers(List.of("u1"))
                        .build()))
                .build();

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "u1"));
    }

    @Test
    void runtimeStateHistoryParticipantIsAllowed() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("initiator")
                .approvalAuditDelta(List.of(ApprovalActionAuditEntry.builder()
                        .actorId("actor")
                        .targetActorId("target")
                        .approvedActors(List.of("approved"))
                        .build()))
                .build();

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "actor"));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "target"));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "approved"));
    }

    @Test
    void runtimeStateOutsiderIsDenied() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("initiator")
                .pendingApprovals(List.of(PendingApproval.builder()
                        .approvers(List.of("approver"))
                        .build()))
                .build();
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.of(instanceInitiatedBy("initiator")));

        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer(state, "outsider"));
    }

    // ---- participants only a task names; administrators, who are none ----

    @Test
    void theApproverATerminationClearedIsAdmittedThroughTheirTask() {
        // Terminated while it waited on "approver", who never acted: neither the state nor the history names them.
        FlowExecutionState terminated = FlowExecutionState.builder()
                .instanceId("i1").initiatorId("initiator").status(FlowExecutionStatus.CANCELLED)
                .recordApproval(true).modelName("ExpenseClaim").rowId("7")
                .approvalAuditDelta(List.of(audit(ApprovalActionType.TERMINATE, "admin")))
                .build();
        when(modelService.count(eq("FlowApprovalTask"), eq(tasksOf("approver", "i1")))).thenReturn(1L);
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.of(instanceInitiatedBy("initiator")));

        assertTrue(guard.isParticipant(terminated, "approver"));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(terminated, "approver"));
        assertEquals(RecordAccess.GRANTED, guard.requireRecordAccess(terminated, "approver"));
        // Its approval records and task list too, whose rows name only those who acted.
        assertDoesNotThrow(() -> guard.requireInstanceViewer("i1", "approver", false));
    }

    @Test
    void anAdministratorWhoOnlyUrgedTerminatedOrReassignedTakesNoPart() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1").initiatorId("initiator").recordApproval(true)
                .approvalAuditDelta(List.of(
                        // An urge entry carries its step's votes so far: whoever they name still takes part.
                        audit(ApprovalActionType.URGE, "admin").toBuilder().approvedActors(List.of("approved")).build(),
                        audit(ApprovalActionType.URGE, "initiator"),
                        // A reassignment is the approver's transfer, its administrator the operator.
                        audit(ApprovalActionType.TRANSFER, "alice").toBuilder()
                                .targetActorId("bob").operatorId("admin3").build(),
                        audit(ApprovalActionType.TERMINATE, "admin2")))
                .build();

        for (String administrator : List.of("admin", "admin2", "admin3")) {
            assertFalse(guard.isParticipant(state, administrator));
            assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer(state, administrator));
            assertThrows(FlowAuthorizationException.class, () -> guard.requireRecordAccess(state, administrator));
        }
        // Asked whether a task of the instance is theirs: none is.
        verify(modelService, atLeastOnce()).count(eq("FlowApprovalTask"), eq(tasksOf("admin", "i1")));
        // The initiator's own urge is beside the point — they take part as its initiator — and so are the others named.
        for (String participant : List.of("initiator", "approved", "alice", "bob")) {
            assertDoesNotThrow(() -> guard.requireInstanceViewer(state, participant));
        }
    }

    @Test
    void aCcRecipientIsAdmittedThroughTheirTask() {
        // The state and history name nobody but the initiator here; the recipient's CC task names them. The tasks are
        // asked whatever their type or status: a CC, read or not, counts as an approval task does.
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1").initiatorId("initiator").status(FlowExecutionStatus.WAITING).build();
        when(modelService.count(eq("FlowApprovalTask"), eq(tasksOf("cc-1", "i1")))).thenReturn(1L);

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "cc-1"));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer(state, "cc-2"));
    }

    @Test
    void whomTheInstanceNamesIsAdmittedWithoutAskingItsTasks() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1").initiatorId("initiator").recordApproval(true)
                .pendingApprovals(List.of(PendingApproval.builder().approvers(List.of("approver")).build()))
                .approvalAuditDelta(List.of(audit(ApprovalActionType.APPROVE, "earlier")))
                .build();

        for (String participant : List.of("initiator", "approver", "earlier")) {
            assertDoesNotThrow(() -> guard.requireInstanceViewer(state, participant));
            assertEquals(RecordAccess.GRANTED, guard.requireRecordAccess(state, participant));
        }
        verify(modelService, never()).count(eq("FlowApprovalTask"), any(Filters.class));
    }

    // ---- everyone else: their own permissions ----

    @Test
    void whoeverTheirRolesLetReadTheInstanceMayViewIt() {
        when(modelService.count(eq("FlowInstance"), any(Filters.class))).thenReturn(1L);
        FlowExecutionState state = FlowExecutionState.builder().instanceId("i1").initiatorId("someoneElse").build();

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "monitor"));
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.of(instanceInitiatedBy("someoneElse")));
        assertDoesNotThrow(() -> guard.requireInstanceViewer("i1", "monitor", false));
    }

    @Test
    void aReaderOfTheRecordMayViewItsApproval() {
        FlowExecutionState approval = FlowExecutionState.builder().instanceId("i1").initiatorId("someoneElse")
                .recordApproval(true).modelName("ExpenseClaim").rowId("7").build();
        FlowExecutionState automation = FlowExecutionState.builder().instanceId("i2").initiatorId("someoneElse")
                .modelName("ExpenseClaim").rowId("7").build();
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
             MockedStatic<IdUtils> ids = mockStatic(IdUtils.class)) {
            models.when(() -> ModelManager.existModel("ExpenseClaim")).thenReturn(true);
            ids.when(() -> IdUtils.formatId(anyString(), any(Serializable.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(modelService.getById(eq("ExpenseClaim"), any(), anyList())).thenReturn(Optional.of(Map.<String, Object>of("id", "7")));

            assertDoesNotThrow(() -> guard.requireInstanceViewer(approval, "reader"));
            // Reading the record says nothing about another flow that merely runs on it.
            assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer(automation, "reader"));
        }
    }

    @Test
    void theInitiatorAndTheTaskHoldersReadTheRecordPastTheirOwnPermissions() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("initiator")
                .recordApproval(true)
                .pendingApprovals(List.of(PendingApproval.builder().approvers(List.of("approver")).build()))
                .build();

        assertEquals(RecordAccess.GRANTED, guard.requireRecordAccess(state, "initiator"));
        assertEquals(RecordAccess.GRANTED, guard.requireRecordAccess(state, "approver"));
    }

    @Test
    void aReaderOfTheRecordReadsItThroughTheirOwnPermissions() {
        FlowExecutionState state = FlowExecutionState.builder().instanceId("i1").initiatorId("initiator")
                .recordApproval(true).modelName("ExpenseClaim").rowId("7").build();
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
             MockedStatic<IdUtils> ids = mockStatic(IdUtils.class)) {
            models.when(() -> ModelManager.existModel("ExpenseClaim")).thenReturn(true);
            ids.when(() -> IdUtils.formatId(anyString(), any(Serializable.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(modelService.getById(eq("ExpenseClaim"), any(), anyList())).thenReturn(Optional.of(Map.<String, Object>of("id", "7")));

            assertEquals(RecordAccess.OWN_PERMISSIONS, guard.requireRecordAccess(state, "reader"));

            // Their permissions no longer reach it: nothing left to read it through.
            when(modelService.getById(eq("ExpenseClaim"), any(), anyList())).thenReturn(Optional.empty());
            assertThrows(FlowAuthorizationException.class, () -> guard.requireRecordAccess(state, "reader"));
        }
    }

    @Test
    void readingTheInstanceRowIsNotReadingItsRecord() {
        // The monitoring console's administrator sees the instance, not the record behind it.
        when(modelService.count(eq("FlowInstance"), any(Filters.class))).thenReturn(1L);
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("initiator")
                .recordApproval(true)
                .build();

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "monitor"));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireRecordAccess(state, "monitor"));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireRecordAccess(state, null));
    }

    @Test
    void onlyARecordsApprovalLendsItsRecord() {
        // An explicit start names its record freely; its initiator must not read it past
        // their own row permissions through the instance, nor learn through it which record a run works on.
        FlowExecutionState namedFreely = FlowExecutionState.builder()
                .instanceId("i2")
                .initiatorId("initiator")
                .modelName("Employee")
                .rowId("42")
                .build();
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
             MockedStatic<IdUtils> ids = mockStatic(IdUtils.class)) {
            models.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            ids.when(() -> IdUtils.formatId(anyString(), any(Serializable.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(modelService.getById(eq("Employee"), any(), anyList())).thenReturn(Optional.of(Map.<String, Object>of("id", "42")));

            assertThrows(FlowAuthorizationException.class, () -> guard.requireRecordAccess(namedFreely, "initiator"));
            assertThrows(FlowAuthorizationException.class, () -> guard.requireRecordAccess(namedFreely, "reader"));
        }
    }

    // ---- a record read through its approvals, whichever is current ----

    @Test
    void aRecordIsReadThroughTheNewestOfItsApprovalsTheCallerTookPartIn() {
        ApprovalActionLedger ledger = mock(ApprovalActionLedger.class);
        FlowInstanceAccessGuard guard = new FlowInstanceAccessGuard(instanceService, new ApprovalAuditReader(ledger),
                modelService);
        // The second submission waits on its approver; another approver returned the first.
        FlowExecutionState second = approvalOf("i2", PendingApproval.builder().approvers(List.of("approver2")).build());
        FlowExecutionState first = approvalOf("i1", null);
        when(ledger.findByInstanceId("i1")).thenReturn(List.of(ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.RETURN).actorId("approver1").build()));
        List<Boolean> skipped = new ArrayList<>();
        when(instanceService.recordApprovalStates("ExpenseClaim", "7")).thenAnswer(invocation -> {
            skipped.add(ContextHolder.getContext().isSkipPermissionCheck());
            return List.of(second, first);
        });

        assertEquals(new RecordAdmission(RecordAccess.GRANTED, second),
                guard.requireRecordAccess("ExpenseClaim", "7", "approver2"));
        assertEquals(new RecordAdmission(RecordAccess.GRANTED, first),
                guard.requireRecordAccess("ExpenseClaim", "7", "approver1"));
        // The initiator of both reads it through the newest.
        assertEquals(new RecordAdmission(RecordAccess.GRANTED, second),
                guard.requireRecordAccess("ExpenseClaim", "7", "initiator"));
        // Read past the caller's permissions, as every lookup of the guard: no business role is granted FlowInstance.
        assertEquals(List.of(true, true, true), skipped);
    }

    @Test
    void whoTookPartInNoneReadsTheRecordWithTheirOwnPermissionsOrIsRefused() {
        FlowExecutionState approval = approvalOf("i1", PendingApproval.builder().approvers(List.of("approver")).build());
        // A run the reader started on the record is not its approval, and lends nothing — the guard does not
        // count on the service to have left such runs out.
        FlowExecutionState otherRun = FlowExecutionState.builder().instanceId("d1").initiatorId("reader")
                .modelName("ExpenseClaim").rowId("7").build();
        when(instanceService.recordApprovalStates("ExpenseClaim", "7")).thenReturn(List.of(otherRun, approval));
        when(instanceService.recordApprovalStates("ExpenseClaim", "8")).thenReturn(List.of());
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
             MockedStatic<IdUtils> ids = mockStatic(IdUtils.class)) {
            models.when(() -> ModelManager.existModel("ExpenseClaim")).thenReturn(true);
            ids.when(() -> IdUtils.formatId(anyString(), any(Serializable.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(modelService.getById(eq("ExpenseClaim"), eq("7"), anyList())).thenReturn(Optional.of(Map.<String, Object>of("id", "7")));
            when(modelService.getById(eq("ExpenseClaim"), eq("8"), anyList())).thenReturn(Optional.of(Map.<String, Object>of("id", "8")));
            RecordAdmission ownPermissions = new RecordAdmission(RecordAccess.OWN_PERMISSIONS, null);

            assertEquals(ownPermissions, guard.requireRecordAccess("ExpenseClaim", "7", "reader"));
            // A record never submitted — a draft its owner opens — is read the same way.
            assertEquals(ownPermissions, guard.requireRecordAccess("ExpenseClaim", "8", "reader"));

            // Their permissions no longer reach it: refused, as is a caller with no identity.
            when(modelService.getById(eq("ExpenseClaim"), eq("7"), anyList())).thenReturn(Optional.empty());
            assertThrows(FlowAuthorizationException.class, () -> guard.requireRecordAccess("ExpenseClaim", "7", "reader"));
            assertThrows(FlowAuthorizationException.class, () -> guard.requireRecordAccess("ExpenseClaim", "7", null));
        }
    }

    @Test
    void aRecordIsLentByTheNewestApprovalThatGaveTheCallerATaskAskedInOneQuery() {
        ApprovalActionLedger ledger = mock(ApprovalActionLedger.class);
        FlowInstanceAccessGuard guard = new FlowInstanceAccessGuard(instanceService, new ApprovalAuditReader(ledger),
                modelService);
        // The second submission was terminated while it waited on approver1 and approver2, neither of whom acted on
        // it; approver1 had approved the first.
        FlowExecutionState second = approvalOf("i2", null);
        FlowExecutionState first = approvalOf("i1", null);
        when(ledger.findByInstanceId("i2")).thenReturn(List.of(audit(ApprovalActionType.TERMINATE, "admin")));
        when(ledger.findByInstanceId("i1")).thenReturn(List.of(audit(ApprovalActionType.APPROVE, "approver1")));
        when(instanceService.recordApprovalStates("ExpenseClaim", "7")).thenReturn(List.of(second, first));
        when(modelService.searchList(eq("FlowApprovalTask"), argThat(tasksQueryOf("approver2", "i2", "i1"))))
                .thenReturn(List.of(Map.of("instanceId", "i2")));
        when(modelService.searchList(eq("FlowApprovalTask"), argThat(tasksQueryOf("approver1", "i2"))))
                .thenReturn(List.of(Map.of("instanceId", "i2")));

        // Named by their task alone, which outlived the termination.
        assertEquals(new RecordAdmission(RecordAccess.GRANTED, second),
                guard.requireRecordAccess("ExpenseClaim", "7", "approver2"));
        // The newest that takes them in, whatever names them: the first's history, the second's task.
        assertEquals(new RecordAdmission(RecordAccess.GRANTED, second),
                guard.requireRecordAccess("ExpenseClaim", "7", "approver1"));
        // The newest names its initiator: no task is asked.
        assertEquals(new RecordAdmission(RecordAccess.GRANTED, second),
                guard.requireRecordAccess("ExpenseClaim", "7", "initiator"));
        // The administrator who terminated it holds no task, and cannot read the record.
        assertThrows(FlowAuthorizationException.class, () -> guard.requireRecordAccess("ExpenseClaim", "7", "admin"));
        // One query a read, however many approvals, and none when their states already answered.
        verify(modelService, times(3)).searchList(eq("FlowApprovalTask"), any(FlexQuery.class));
    }

    /** A user's tasks on these instances, as the guard asks for them: whatever their type and status. */
    private static Filters tasksOf(String userId, String... instanceIds) {
        return new Filters()
                .in(FlowApprovalTask::getInstanceId, List.of(instanceIds))
                .eq(FlowApprovalTask::getActorId, userId);
    }

    private static ArgumentMatcher<FlexQuery> tasksQueryOf(String userId, String... instanceIds) {
        return query -> query != null && tasksOf(userId, instanceIds).equals(query.getFilters());
    }

    private static ApprovalActionAuditEntry audit(ApprovalActionType action, String actorId) {
        return ApprovalActionAuditEntry.builder().action(action).actorId(actorId).build();
    }

    /** A record's approval of ExpenseClaim 7, submitted by "initiator", waiting on {@code step} unless it is null. */
    private static FlowExecutionState approvalOf(String instanceId, PendingApproval step) {
        return FlowExecutionState.builder()
                .instanceId(instanceId)
                .initiatorId("initiator")
                .recordApproval(true)
                .modelName("ExpenseClaim")
                .rowId("7")
                .pendingApprovals(step == null ? List.of() : List.of(step))
                .build();
    }

    @Test
    void withoutAnyOfThemTheCallerIsDenied() {
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.of(instanceInitiatedBy("someoneElse")));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer("i1", "u1", false));
    }

    @Test
    void anAdministratorIsWhoeverTheirOwnPermissionsLetReadTheInstance() {
        when(modelService.count(eq("FlowInstance"), any(Filters.class))).thenReturn(1L);
        assertDoesNotThrow(() -> guard.requireInstanceAdministrator("i1", "admin"));
    }

    @Test
    void anInstanceOutsideTheCallersScopeIsNotTheirsToAdminister() {
        // Taking part in it does not count: the console's actions are not a participant's.
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.of(instanceInitiatedBy("u1")));
        when(modelService.count(eq("FlowInstance"), any(Filters.class))).thenReturn(0L);
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceAdministrator("i1", "u1"));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceAdministrator("i1", null));
    }
}
