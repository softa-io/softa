package io.softa.starter.flow.service.support;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.SystemRole;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.runtime.state.ReturnedApprovalContext;
import io.softa.starter.flow.runtime.store.FlowInstanceStore;
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
    private final FlowInstanceStore instanceStore = mock(FlowInstanceStore.class);
    private final FlowInstanceAccessGuard guard = new FlowInstanceAccessGuard(instanceService,
            new ApprovalAuditReader(new NoopApprovalActionLedger()), instanceStore);

    private FlowExecutionState stateInitiatedBy(String initiatorId) {
        return FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId(initiatorId)
                .build();
    }

    @Test
    void participantInResultIsAllowed() {
        // A caller who appears in the loaded rows is allowed without touching the instance store.
        assertDoesNotThrow(() -> guard.requireInstanceViewer("i1", "u1", true));
    }

    @Test
    void initiatorIsAllowedEvenWhenNotInResult() {
        when(instanceStore.get("i1")).thenReturn(Optional.of(stateInitiatedBy("u1")));
        assertDoesNotThrow(() -> guard.requireInstanceViewer("i1", "u1", false));
    }

    @Test
    void pendingApproverNotYetInResultIsAllowed() {
        // A pending approver has no approval-record row yet, so the records-derived
        // participantInResult is false for exactly the caller whose task sits in the inbox —
        // the runtime-state fallback must admit them via the pending approvals.
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("initiator")
                .pendingApprovals(List.of(PendingApproval.builder()
                        .approvers(List.of("u1"))
                        .build()))
                .build();
        when(instanceStore.get("i1")).thenReturn(Optional.of(state));

        assertDoesNotThrow(() -> guard.requireInstanceViewer("i1", "u1", false));
    }

    @Test
    void nonParticipantNonInitiatorIsDenied() {
        when(instanceStore.get("i1")).thenReturn(Optional.of(stateInitiatedBy("someoneElse")));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer("i1", "u1", false));
    }

    @Test
    void missingInstanceIsDenied() {
        when(instanceStore.get("i1")).thenReturn(Optional.empty());
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
        FlowInstance entity = new FlowInstance();
        entity.setInitiatorId("initiator");
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.of(entity));

        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer(state, "outsider"));
    }

    // ---- monitor-admin bypass ----

    private static Context contextWithRoles(Set<String> roleCodes) {
        Context ctx = new Context();
        ctx.setRoleCodes(roleCodes);
        return ctx;
    }

    @Test
    void monitorAdminBypassesParticipantScoping() {
        Context ctx = contextWithRoles(Set.of(SystemRole.SYSTEM_ROLE_ADMIN.getCode()));
        ContextHolder.runWith(ctx, () ->
                assertDoesNotThrow(() -> guard.requireInstanceViewer("i1", "adminUser", false)));
    }

    @Test
    void monitorAdminBypassesStateScoping() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("someoneElse")
                .build();
        Context ctx = contextWithRoles(Set.of(SystemRole.SYSTEM_ROLE_ADMIN.getCode()));
        ContextHolder.runWith(ctx, () ->
                assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "adminUser")));
    }

    @Test
    void nonAdminRoleIsStillDenied() {
        when(instanceStore.get("i1")).thenReturn(Optional.of(stateInitiatedBy("someoneElse")));
        Context ctx = contextWithRoles(Set.of("SomeOtherRole"));
        ContextHolder.runWith(ctx, () ->
                assertThrows(FlowAuthorizationException.class,
                        () -> guard.requireInstanceViewer("i1", "u1", false)));
    }

    @Test
    void absentRoleProviderFailsClosed() {
        // A bound context without permission info — e.g. no role provider wired by the host app.
        when(instanceStore.get("i1")).thenReturn(Optional.of(stateInitiatedBy("someoneElse")));
        ContextHolder.runWith(new Context(), () ->
                assertThrows(FlowAuthorizationException.class,
                        () -> guard.requireInstanceViewer("i1", "u1", false)));
    }

    @Test
    void adminRoleDoesNotBypassAuthentication() {
        Context ctx = contextWithRoles(Set.of(SystemRole.SYSTEM_ROLE_ADMIN.getCode()));
        ContextHolder.runWith(ctx, () ->
                assertThrows(FlowAuthorizationException.class,
                        () -> guard.requireInstanceViewer("i1", "", false)));
    }
}
