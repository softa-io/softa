package io.softa.starter.flow.service.support;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.runtime.state.ReturnedApprovalContext;
import io.softa.starter.flow.service.FlowInstanceService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FlowInstanceAccessGuard} — participant-scoping of the
 * by-instance approval read endpoints (R4 fix).
 */
class FlowInstanceAccessGuardTest {

    private final FlowInstanceService instanceService = mock(FlowInstanceService.class);
    private final FlowInstanceAccessGuard guard = new FlowInstanceAccessGuard(instanceService,
            new ApprovalAuditReader(new NoopApprovalActionLedger()));

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
    void runtimeStateAddSignParticipantsAreAllowed() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("initiator")
                .pendingApprovals(List.of(PendingApproval.builder()
                        .blockedActorId("blocked")
                        .prerequisiteActorId("prereq")
                        .build()))
                .build();

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "blocked"));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "prereq"));
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
                        .rejectedActors(List.of("rejected"))
                        .build()))
                .build();

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "actor"));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "target"));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "approved"));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "rejected"));
    }

    @Test
    void runtimeStateReturnedApprovalParticipantIsAllowed() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("initiator")
                .returnedApproval(ReturnedApprovalContext.builder()
                        .actorId("returner")
                        .targetActorId("target")
                        .pendingApproval(PendingApproval.builder()
                                .approvers(List.of("reopened"))
                                .build())
                        .build())
                .build();

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "returner"));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "target"));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "reopened"));
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
}
