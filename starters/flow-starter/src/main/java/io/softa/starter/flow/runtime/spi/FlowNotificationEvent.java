package io.softa.starter.flow.runtime.spi;

import java.util.List;

import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Sealed event hierarchy for the {@link ApprovalNotificationService} SPI.
 * Each variant carries the runtime state at emission time plus any context
 * not already in state.
 */
public sealed interface FlowNotificationEvent {

    FlowExecutionState state();

    record TaskAssigned(FlowExecutionState state, PendingApproval pendingApproval) implements FlowNotificationEvent {}
    record TaskCompleted(FlowExecutionState state, PendingApproval pendingApproval, boolean approved) implements FlowNotificationEvent {}
    record TaskTransferred(FlowExecutionState state, PendingApproval pendingApproval, String newActorId) implements FlowNotificationEvent {}
    record TimeoutReminded(FlowExecutionState state, PendingApproval pendingApproval, int remindCount) implements FlowNotificationEvent {}
    record FlowCompleted(FlowExecutionState state, boolean approved) implements FlowNotificationEvent {}
    record Urged(FlowExecutionState state, List<String> pendingActorIds, String urgerId, String message) implements FlowNotificationEvent {}
    record CcSent(FlowExecutionState state, String nodeId, List<String> recipientActorIds, String message) implements FlowNotificationEvent {}
    record Returned(FlowExecutionState state, PendingApproval pendingApproval, String actorId, String comment) implements FlowNotificationEvent {}
    /** The instance failed: a step could not run (a flow configuration error, an approver who cannot be found). */
    record FlowFailed(FlowExecutionState state, String failedNodeId, String errorMessage) implements FlowNotificationEvent {}
    /** An administrator ended the approval; its record is cancelled. */
    record Terminated(FlowExecutionState state, String actorId, String reason) implements FlowNotificationEvent {}
    /** An administrator moved a step of the approval from one approver to another. */
    record Reassigned(FlowExecutionState state, PendingApproval pendingApproval, String fromActorId, String toActorId,
                      String reason) implements FlowNotificationEvent {}
}
