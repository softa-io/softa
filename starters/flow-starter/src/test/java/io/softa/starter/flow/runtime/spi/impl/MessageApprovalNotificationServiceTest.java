package io.softa.starter.flow.runtime.spi.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.inbox.enums.NotificationType;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Who hears about what: approvers about their tasks, CC recipients about the copy, the initiator about the result —
 * in the inbox, as Workflow messages pointing at the flow instance, and by email with the notice's template and a link
 * to the approval's page on a flow that sends email. Written after the action commits, each channel on its own, and
 * never failing the action.
 */
class MessageApprovalNotificationServiceTest {

    private final MessageService messageService = mock(MessageService.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final FlowBundleRegistry bundleRegistry = mock(FlowBundleRegistry.class);
    private final SystemConfig systemConfig = new SystemConfig();
    private final AtomicInteger administratorQueries = new AtomicInteger();
    private MessageApprovalNotificationService notifications;
    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<SystemConfig> config = mock(ObjectProvider.class);
        when(config.getIfAvailable()).thenReturn(systemConfig);
        systemConfig.setSiteUrl("https://hr.example.com");
        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel("UserAccount")).thenReturn(true);
        when(modelService.getByIds(eq("UserAccount"), anyList(), any())).thenReturn(List.of(
                Map.of("id", 2001L, "email", "mia@example.com"),
                Map.of("id", 2002L)));
        when(bundleRegistry.getByBundleId(7L)).thenReturn(Optional.of(CompiledFlowDefinition.builder().build()));
        notifications = new MessageApprovalNotificationService(messageService, modelService,
                mock(PlatformTransactionManager.class), bundleRegistry, config, () -> {
                    administratorQueries.incrementAndGet();
                    return List.of("admin-1");
                });
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    // ── Inbox ─────────────────────────────────────────────────────────────

    @Test
    void theStepsApproversAreToldTheyHaveATask() {
        PendingApproval step = PendingApproval.builder().nodeId("manager").approvers(List.of("2001", "2002", "robot")).build();

        notifications.notify(new FlowNotificationEvent.TaskAssigned(state(), step));

        List<SendInboxDTO> sent = inbox();
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

        assertEquals(List.of(2001L), inbox().stream().map(SendInboxDTO::getRecipientId).toList());
    }

    @Test
    void whoeverATaskIsTransferredToIsToldTheyHaveIt() {
        PendingApproval step = PendingApproval.builder().nodeId("manager").approvers(List.of("2001")).build();

        notifications.notify(new FlowNotificationEvent.TaskTransferred(state(), step, "2003"));

        ArgumentCaptor<List<SendInboxDTO>> batches = captor();
        verify(messageService, times(1)).sendInboxBatch(batches.capture());
        assertEquals(2003L, batches.getValue().getFirst().getRecipientId());
        assertEquals("Pending approval: Leave of Alice", batches.getValue().getFirst().getTitle());
    }

    @Test
    void aCcRecipientIsToldWithTheMessageInTheInboxOnly() {
        notifications.notify(new FlowNotificationEvent.CcSent(state(), "manager", List.of("3001"), "for the record"));

        SendInboxDTO sent = inbox().getFirst();
        assertEquals(3001L, sent.getRecipientId());
        assertEquals("For your information: Leave of Alice", sent.getTitle());
        assertEquals("for the record", sent.getContent());
        verify(messageService, never()).sendMailBatch(any());
    }

    @Test
    void theApproversStillToActAreToldTheirStepIsOverdue() {
        // The submitter in their own step, approved on record when the step opened.
        PendingApproval step = PendingApproval.builder().nodeId("manager").approvers(List.of("1001", "2001", "robot"))
                .approvedActors(List.of("1001")).build();

        notifications.notify(new FlowNotificationEvent.TimeoutReminded(state(), step, 1));

        List<SendInboxDTO> sent = inbox();
        assertEquals(List.of(2001L), sent.stream().map(SendInboxDTO::getRecipientId).toList());
        assertEquals("Approval overdue: Leave of Alice", sent.getFirst().getTitle());
    }

    @Test
    void messagesAreWrittenOnlyOnceTheActionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            notifications.notify(new FlowNotificationEvent.FlowCompleted(state()));
            verify(messageService, never()).sendInboxBatch(any());
            verify(messageService, never()).sendMailBatch(any());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertEquals("Approved: Leave of Alice", inbox().getFirst().getTitle());
        assertEquals(ApprovalNotices.APPROVED, mail().getFirst().getTemplateCode());
    }

    @Test
    void aMessageThatCannotBeWrittenDoesNotFailTheAction() {
        when(messageService.sendInboxBatch(any())).thenThrow(new IllegalStateException("inbox is down"));

        assertDoesNotThrow(() -> notifications.notify(new FlowNotificationEvent.FlowCompleted(state())));
    }

    @Test
    void anInboxThatCannotBeWrittenStillLetsTheEmailGo() {
        when(messageService.sendInboxBatch(any())).thenThrow(new IllegalStateException("inbox is down"));

        notifications.notify(new FlowNotificationEvent.FlowCompleted(state()));

        assertEquals(List.of("mia@example.com"), mail().getFirst().getTo());
    }

    @Test
    void aLongTitleIsCutToTheInboxWidth() {
        FlowExecutionState state = state();
        state.setTitle("x".repeat(300));

        notifications.notify(new FlowNotificationEvent.FlowCompleted(state));

        assertEquals(200, inbox().getFirst().getTitle().length());
    }

    @Test
    void theInitiatorHearsOfARejectionWithItsComment() {
        FlowExecutionState state = state();
        state.getApprovalAuditDelta().add(ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.REJECT).actorId("2001").comment("busy week").build());

        notifications.notify(new FlowNotificationEvent.Rejected(state, PendingApproval.builder().build()));

        SendInboxDTO sent = inbox().getFirst();
        assertEquals(1001L, sent.getRecipientId());
        assertEquals("Rejected: Leave of Alice", sent.getTitle());
        assertEquals("busy week", sent.getContent());
    }

    @Test
    void theInitiatorHearsOfAReturnAndOfTheApproval() {
        notifications.notify(new FlowNotificationEvent.Returned(state(), PendingApproval.builder().build(), "2001", "fix the dates"));
        notifications.notify(new FlowNotificationEvent.FlowCompleted(state()));

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

        List<SendInboxDTO> sent = inbox();
        assertEquals(List.of(1001L), sent.stream().map(SendInboxDTO::getRecipientId).toList(),
                "admin-1 is not a numeric account id, so only the initiator is addressable");
        assertEquals("Approval failed: Leave of Alice", sent.getFirst().getTitle());
        assertEquals("No approver found", sent.getFirst().getContent());
        assertEquals(1, administratorQueries.get(), "the administrators are looked up once for both channels");
    }

    @Test
    void anAutomationRunThatFailsTellsNobody() {
        notifications.notify(new FlowNotificationEvent.FlowFailed(state(), "step", "boom"));

        verify(messageService, never()).sendInboxBatch(any());
        verify(messageService, never()).sendMailBatch(any());
    }

    // ── Email ─────────────────────────────────────────────────────────────

    @Test
    void aNewTaskIsEmailedWithItsTemplateAndALinkToTheApproval() {
        notifications.notify(new FlowNotificationEvent.TaskAssigned(state(),
                PendingApproval.builder().nodeId("manager").approvers(List.of("2001", "2002")).build()));

        List<SendMailDTO> sent = mail();
        assertEquals(1, sent.size(), "an account without an email is skipped");
        SendMailDTO message = sent.getFirst();
        assertEquals(List.of("mia@example.com"), message.getTo());
        assertEquals(ApprovalNotices.TASK_ASSIGNED, message.getTemplateCode());
        assertEquals("https://hr.example.com/home/approvals/i1", message.getTemplateVariables().get("link"));
        assertEquals("Leave of Alice", message.getTemplateVariables().get("subject"));
        assertEquals("LR-000042", message.getTemplateVariables().get("recordCode"));
    }

    @Test
    void aFlowThatTurnedEmailOffSendsNoneButStillTellsTheInbox() {
        when(bundleRegistry.getByBundleId(7L))
                .thenReturn(Optional.of(CompiledFlowDefinition.builder().emailNotification(false).build()));

        notifications.notify(new FlowNotificationEvent.FlowCompleted(state()));

        verify(messageService, never()).sendMailBatch(any());
        assertEquals("Approved: Leave of Alice", inbox().getFirst().getTitle());
    }

    private List<SendInboxDTO> inbox() {
        ArgumentCaptor<List<SendInboxDTO>> batch = captor();
        verify(messageService).sendInboxBatch(batch.capture());
        return batch.getValue();
    }

    private List<SendMailDTO> mail() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<SendMailDTO>> batch = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(messageService).sendMailBatch(batch.capture());
        return batch.getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<SendInboxDTO>> captor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private static FlowExecutionState state() {
        return FlowExecutionState.builder()
                .instanceId("i1")
                .bundleId(7L)
                .title("Leave of Alice")
                .recordCode("LR-000042")
                .initiatorId("1001")
                .modelName("LeaveRequest")
                .rowId("42")
                .approvalAuditDelta(new ArrayList<>())
                .build();
    }
}
