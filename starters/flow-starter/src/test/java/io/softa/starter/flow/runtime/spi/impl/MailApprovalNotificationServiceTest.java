package io.softa.starter.flow.runtime.spi.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The email channel: the notice's template, to each recipient's account email, with a link to the approval's
 * page — and only on a flow that sends email.
 */
class MailApprovalNotificationServiceTest {

    private final MessageService messageService = mock(MessageService.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final FlowBundleRegistry bundleRegistry = mock(FlowBundleRegistry.class);
    private final SystemConfig systemConfig = new SystemConfig();
    private MailApprovalNotificationService mail;
    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<MessageService> messages = mock(ObjectProvider.class);
        when(messages.getIfAvailable()).thenReturn(messageService);
        ObjectProvider<SystemConfig> config = mock(ObjectProvider.class);
        when(config.getIfAvailable()).thenReturn(systemConfig);
        systemConfig.setSiteUrl("https://hr.example.com");
        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel("UserAccount")).thenReturn(true);
        when(modelService.getByIds(eq("UserAccount"), anyList(), any())).thenReturn(List.of(
                Map.of("id", 2001L, "email", "mia@example.com"),
                Map.of("id", 2002L)));
        mail = new MailApprovalNotificationService(messages, modelService, mock(PlatformTransactionManager.class),
                bundleRegistry, config, List::of);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void aNewTaskIsEmailedWithItsTemplateAndALinkToTheApproval() {
        when(bundleRegistry.getByBundleId(7L)).thenReturn(Optional.of(CompiledFlowDefinition.builder().build()));

        mail.notify(new FlowNotificationEvent.TaskAssigned(state(),
                PendingApproval.builder().nodeId("manager").approvers(List.of("2001", "2002")).build()));

        List<SendMailDTO> sent = sent();
        assertEquals(1, sent.size(), "an account without an email is skipped");
        SendMailDTO message = sent.getFirst();
        assertEquals(List.of("mia@example.com"), message.getTo());
        assertEquals(ApprovalNotices.TASK_ASSIGNED, message.getTemplateCode());
        assertEquals("https://hr.example.com/home/approvals/i1", message.getTemplateVariables().get("link"));
        assertEquals("Leave of Alice", message.getTemplateVariables().get("subject"));
        assertEquals("LR-000042", message.getTemplateVariables().get("recordCode"));
    }

    @Test
    void aFlowThatTurnedEmailOffSendsNone() {
        when(bundleRegistry.getByBundleId(7L))
                .thenReturn(Optional.of(CompiledFlowDefinition.builder().emailNotification(false).build()));

        mail.notify(new FlowNotificationEvent.FlowCompleted(state(), true));

        verify(messageService, never()).sendMailBatch(any());
    }

    @Test
    void aCcIsNotEmailed() {
        when(bundleRegistry.getByBundleId(7L)).thenReturn(Optional.of(CompiledFlowDefinition.builder().build()));

        mail.notify(new FlowNotificationEvent.CcSent(state(), "manager", List.of("2001"), null));

        verify(messageService, never()).sendMailBatch(any());
    }

    private List<SendMailDTO> sent() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<SendMailDTO>> batch = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(messageService).sendMailBatch(batch.capture());
        return batch.getValue();
    }

    private static FlowExecutionState state() {
        return FlowExecutionState.builder()
                .instanceId("i1")
                .bundleId(7L)
                .title("Leave of Alice")
                .recordCode("LR-000042")
                .initiatorId("1001")
                .approvalAuditDelta(new ArrayList<>())
                .build();
    }
}
