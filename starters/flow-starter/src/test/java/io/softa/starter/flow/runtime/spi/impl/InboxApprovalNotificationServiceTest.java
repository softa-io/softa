package io.softa.starter.flow.runtime.spi.impl;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.inbox.enums.NotificationType;
import io.softa.starter.message.service.MessageService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Who hears about what: approvers about their tasks, CC recipients about the copy, the initiator about the
 * result, every message a Workflow inbox message pointing at the flow instance — written after the action
 * commits, and never failing it.
 */
class InboxApprovalNotificationServiceTest {

    private final MessageService messageService = mock(MessageService.class);
    private final InboxApprovalNotificationService notifications;

    @SuppressWarnings("unchecked")
    InboxApprovalNotificationServiceTest() {
        ObjectProvider<MessageService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(messageService);
        notifications = new InboxApprovalNotificationService(provider, mock(ModelService.class),
                mock(PlatformTransactionManager.class), () -> List.of("admin-1"));
    }

    @Test
    void theStepsApproversAreToldTheyHaveATask() {
        PendingApproval step = PendingApproval.builder().nodeId("manager").approvers(List.of("2001", "2002", "robot")).build();

        notifications.notify(new FlowNotificationEvent.TaskAssigned(state(), step));

        List<SendInboxDTO> sent = sent();
        assertEquals(List.of(2001L, 2002L), sent.stream().map(SendInboxDTO::getRecipientId).toList());
        assertEquals("Pending approval: Leave of Alice", sent.getFirst().getTitle());
        assertEquals(NotificationType.WORKFLOW, sent.getFirst().getNotificationType());
        assertEquals("FlowInstance", sent.getFirst().getSourceModel());
        assertEquals("i1", sent.getFirst().getSourceId());
    }

    @Test
    void anApproverWhoAlreadyApprovedTheStepIsNotToldOfIt() {
        // The submitter in their own step, approved on record when the step opened.
        PendingApproval step = PendingApproval.builder().nodeId("manager").approvers(List.of("1001", "2001"))
                .approvedActors(List.of("1001")).build();

        notifications.notify(new FlowNotificationEvent.TaskAssigned(state(), step));

        assertEquals(List.of(2001L), sent().stream().map(SendInboxDTO::getRecipientId).toList());
    }

    @Test
    void whoeverATaskIsTransferredOrDelegatedToIsToldTheyHaveIt() {
        PendingApproval step = PendingApproval.builder().nodeId("manager").approvers(List.of("2001")).build();

        notifications.notify(new FlowNotificationEvent.TaskTransferred(state(), step, "2003"));
        notifications.notify(new FlowNotificationEvent.TaskDelegated(state(), step, "2001", "2004"));

        ArgumentCaptor<List<SendInboxDTO>> batches = captor();
        verify(messageService, times(2)).sendInboxBatch(batches.capture());
        assertEquals(2003L, batches.getAllValues().get(0).getFirst().getRecipientId());
        assertEquals(2004L, batches.getAllValues().get(1).getFirst().getRecipientId());
        batches.getAllValues().forEach(batch ->
                assertEquals("Pending approval: Leave of Alice", batch.getFirst().getTitle()));
    }

    @Test
    void aCcRecipientIsToldWithTheMessage() {
        notifications.notify(new FlowNotificationEvent.CcSent(state(), "manager", List.of("3001"), "for the record"));

        SendInboxDTO sent = sent().getFirst();
        assertEquals(3001L, sent.getRecipientId());
        assertEquals("For your information: Leave of Alice", sent.getTitle());
        assertEquals("for the record", sent.getContent());
    }

    @Test
    void theApproversStillToActAreToldTheirStepIsOverdue() {
        // The submitter in their own step, approved on record when the step opened.
        PendingApproval step = PendingApproval.builder().nodeId("manager").approvers(List.of("1001", "2001", "robot"))
                .approvedActors(List.of("1001")).build();

        notifications.notify(new FlowNotificationEvent.TimeoutReminded(state(), step, 1));

        List<SendInboxDTO> sent = sent();
        assertEquals(List.of(2001L), sent.stream().map(SendInboxDTO::getRecipientId).toList());
        assertEquals("Approval overdue: Leave of Alice", sent.getFirst().getTitle());
        assertEquals(NotificationType.WORKFLOW, sent.getFirst().getNotificationType());
        assertEquals("FlowInstance", sent.getFirst().getSourceModel());
        assertEquals("i1", sent.getFirst().getSourceId());
    }

    @Test
    void messagesAreWrittenOnlyOnceTheActionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            notifications.notify(new FlowNotificationEvent.FlowCompleted(state(), true));
            verify(messageService, never()).sendInboxBatch(any());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertEquals("Approved: Leave of Alice", sent().getFirst().getTitle());
    }

    @Test
    void aMessageThatCannotBeWrittenDoesNotFailTheAction() {
        when(messageService.sendInboxBatch(any())).thenThrow(new IllegalStateException("inbox is down"));

        assertDoesNotThrow(() -> notifications.notify(new FlowNotificationEvent.FlowCompleted(state(), true)));
    }

    @Test
    void aLongTitleIsCutToTheInboxWidth() {
        FlowExecutionState state = state();
        state.setTitle("x".repeat(300));

        notifications.notify(new FlowNotificationEvent.FlowCompleted(state, true));

        assertEquals(200, sent().getFirst().getTitle().length());
    }

    @Test
    void theInitiatorHearsOfARejectionWithItsComment() {
        FlowExecutionState state = state();
        state.getApprovalAuditDelta().add(ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.REJECT).actorId("2001").comment("busy week").build());

        notifications.notify(new FlowNotificationEvent.TaskCompleted(state, PendingApproval.builder().build(), false));

        SendInboxDTO sent = sent().getFirst();
        assertEquals(1001L, sent.getRecipientId());
        assertEquals("Rejected: Leave of Alice", sent.getTitle());
        assertEquals("busy week", sent.getContent());
    }

    @Test
    void theInitiatorHearsOfAReturnAndOfTheApprovalButNotOfEachApprovedStep() {
        notifications.notify(new FlowNotificationEvent.TaskCompleted(state(), PendingApproval.builder().build(), true));
        verify(messageService, never()).sendInboxBatch(any());

        notifications.notify(new FlowNotificationEvent.Returned(state(), PendingApproval.builder().build(), "2001", "fix the dates"));
        notifications.notify(new FlowNotificationEvent.FlowCompleted(state(), true));

        ArgumentCaptor<List<SendInboxDTO>> batches = captor();
        verify(messageService, times(2)).sendInboxBatch(batches.capture());
        assertEquals("Returned to you: Leave of Alice", batches.getAllValues().get(0).getFirst().getTitle());
        assertEquals("fix the dates", batches.getAllValues().get(0).getFirst().getContent());
        assertEquals("Approved: Leave of Alice", batches.getAllValues().get(1).getFirst().getTitle());
    }

    @Test
    void aFailedApprovalTellsItsInitiatorAndTheAdministratorsAtOnce() {
        FlowExecutionState state = state();
        state.setRecordApproval(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            // The failed attempt's transaction rolls back: the notice cannot wait for its commit.
            notifications.notify(new FlowNotificationEvent.FlowFailed(state, "manager", "No approver found"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        List<SendInboxDTO> sent = sent();
        assertEquals(List.of(1001L), sent.stream().map(SendInboxDTO::getRecipientId).toList(),
                "admin-1 is not a numeric account id, so only the initiator is addressable");
        assertEquals("Approval failed: Leave of Alice", sent.getFirst().getTitle());
        assertEquals("No approver found", sent.getFirst().getContent());
    }

    @Test
    void anAutomationRunThatFailsTellsNobody() {
        notifications.notify(new FlowNotificationEvent.FlowFailed(state(), "step", "boom"));

        verify(messageService, never()).sendInboxBatch(any());
    }

    private List<SendInboxDTO> sent() {
        ArgumentCaptor<List<SendInboxDTO>> batch = captor();
        verify(messageService).sendInboxBatch(batch.capture());
        return batch.getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<SendInboxDTO>> captor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private static FlowExecutionState state() {
        return FlowExecutionState.builder()
                .instanceId("i1")
                .title("Leave of Alice")
                .initiatorId("1001")
                .modelName("LeaveRequest")
                .rowId("42")
                .approvalAuditDelta(new ArrayList<>())
                .build();
    }
}
