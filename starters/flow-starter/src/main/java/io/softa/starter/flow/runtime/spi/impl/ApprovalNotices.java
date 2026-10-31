package io.softa.starter.flow.runtime.spi.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Who an approval event concerns and what they are told — the one mapping every channel sends: the inbox always,
 * email on a flow that sends it. Each notice names its mail template by a fixed code; a notice with none is not
 * emailed (a CC, a timeout reminder). A task or a CC is also listed in My Tasks; the inbox message says it arrived.
 *
 * <ul>
 *   <li>A step's approvers still to act, and whoever a task is transferred or delegated to: "Pending approval".</li>
 *   <li>CC recipients: "For your information".</li>
 *   <li>Approvers urged, or reminded of a step past its deadline: "Reminder" / "Approval overdue".</li>
 *   <li>The initiator, when the approval is approved, rejected or returned (with the comment), terminated or
 *       reassigned by an administrator (with the reason).</li>
 *   <li>The initiator and the tenant's administrators, when the approval fails.</li>
 * </ul>
 */
final class ApprovalNotices {

    static final String TASK_ASSIGNED = "flow.task-assigned";
    static final String URGED = "flow.urged";
    static final String APPROVED = "flow.approved";
    static final String REJECTED = "flow.rejected";
    static final String RETURNED = "flow.returned";
    static final String TERMINATED = "flow.terminated";
    static final String REASSIGNED = "flow.reassigned";
    static final String FAILED = "flow.failed";

    private ApprovalNotices() {
    }

    /** Who to tell, the title template (its {0} is the instance's subject), an optional body, and the mail template. */
    record Notice(List<String> recipients, String title, String content, String mailTemplate) {
    }

    /**
     * The notice for an event, or null when nobody is told. {@code administrators} is asked only for a failure:
     * the users who fix a flow's configuration or end the approval.
     */
    static Notice of(FlowNotificationEvent event, Supplier<List<String>> administrators) {
        FlowExecutionState state = event.state();
        return switch (event) {
            case FlowNotificationEvent.TaskAssigned e ->
                    new Notice(stillToAct(e.pendingApproval()), "Pending approval: {0}", null, TASK_ASSIGNED);
            case FlowNotificationEvent.TaskTransferred e ->
                    new Notice(List.of(e.newActorId()), "Pending approval: {0}", null, TASK_ASSIGNED);
            case FlowNotificationEvent.TaskDelegated e ->
                    new Notice(List.of(e.toActorId()), "Pending approval: {0}", null, TASK_ASSIGNED);
            case FlowNotificationEvent.CcSent e ->
                    new Notice(e.recipientActorIds(), "For your information: {0}", e.message(), null);
            case FlowNotificationEvent.Urged e ->
                    new Notice(e.pendingActorIds(), "Reminder to approve: {0}", e.message(), URGED);
            case FlowNotificationEvent.TimeoutReminded e ->
                    new Notice(stillToAct(e.pendingApproval()), "Approval overdue: {0}", null, null);
            case FlowNotificationEvent.FlowCompleted e -> e.approved()
                    ? new Notice(List.of(state.getInitiatorId()), "Approved: {0}", null, APPROVED)
                    : null;
            // Approving one step is no news to the initiator; a rejection ends the approval.
            case FlowNotificationEvent.TaskCompleted e -> e.approved()
                    ? null
                    : new Notice(List.of(state.getInitiatorId()), "Rejected: {0}",
                            latestComment(state, ApprovalActionType.REJECT), REJECTED);
            case FlowNotificationEvent.Returned e ->
                    new Notice(List.of(state.getInitiatorId()), "Returned to you: {0}", e.comment(), RETURNED);
            case FlowNotificationEvent.Terminated e ->
                    new Notice(List.of(state.getInitiatorId()), "Terminated: {0}", e.reason(), TERMINATED);
            // The new approver hears of the task as of any transfer; the initiator, of who now holds it.
            case FlowNotificationEvent.Reassigned e ->
                    new Notice(List.of(state.getInitiatorId()), "Approver changed: {0}", e.reason(), REASSIGNED);
            // Only a record's approval: an automation run that fails is the operators' to watch, not its trigger's.
            case FlowNotificationEvent.FlowFailed e -> Boolean.TRUE.equals(state.getRecordApproval())
                    ? new Notice(withInitiator(state, administrators.get()), "Approval failed: {0}", e.errorMessage(), FAILED)
                    : null;
        };
    }

    /** What the instance is called in a message: its title, else its flow's name. */
    static String subjectOf(FlowExecutionState state) {
        if (StringUtils.hasText(state.getTitle())) {
            return state.getTitle();
        }
        return StringUtils.hasText(state.getFlowName()) ? state.getFlowName() : state.getInstanceId();
    }

    private static List<String> withInitiator(FlowExecutionState state, List<String> others) {
        List<String> recipients = new ArrayList<>();
        if (state.getInitiatorId() != null) {
            recipients.add(state.getInitiatorId());
        }
        if (others != null) {
            recipients.addAll(others);
        }
        return recipients;
    }

    /** The approvers of a step who have not acted yet. */
    private static List<String> stillToAct(PendingApproval step) {
        List<String> approvers = step.getApprovers() == null ? List.of() : step.getApprovers();
        return approvers.stream()
                .filter(approver -> step.getApprovedActors() == null || !step.getApprovedActors().contains(approver))
                .filter(approver -> step.getRejectedActors() == null || !step.getRejectedActors().contains(approver))
                .toList();
    }

    /** The comment of the latest {@code action} the current action recorded. */
    private static String latestComment(FlowExecutionState state, ApprovalActionType action) {
        List<ApprovalActionAuditEntry> delta = state.getApprovalAuditDelta();
        if (delta == null) {
            return null;
        }
        for (int i = delta.size() - 1; i >= 0; i--) {
            if (action.equals(delta.get(i).getAction())) {
                return delta.get(i).getComment();
            }
        }
        return null;
    }
}
