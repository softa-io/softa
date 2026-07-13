package io.softa.starter.flow.runtime.task.builtin;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.enums.MailPriority;
import io.softa.starter.message.service.MessageService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SendEmailTaskExecutor}.
 */
class SendEmailTaskExecutorTest {

    private MessageService messageService;
    private SendEmailTaskExecutor executor;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        executor = new SendEmailTaskExecutor(messageService);
    }

    @Test
    void reportsSupportedNodeType() {
        assertEquals(FlowNodeType.SEND_EMAIL, executor.getSupportedNodeType());
    }

    @Test
    void directSendBuildsDtoAndReturnsResult() {
        when(messageService.sendMail(any(SendMailDTO.class))).thenReturn(1L);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.SEND_EMAIL)
                .input(Map.of(
                        "to", List.of("user@example.com"),
                        "subject", "Order confirmed",
                        "htmlBody", "<h1>Thanks</h1>",
                        "priority", "high"))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of());

        assertEquals(Boolean.TRUE, result.get("sent"));
        assertEquals("EMAIL", result.get("channel"));
        assertEquals(List.of("user@example.com"), result.get("to"));

        ArgumentCaptor<SendMailDTO> captor = ArgumentCaptor.forClass(SendMailDTO.class);
        verify(messageService).sendMail(captor.capture());
        SendMailDTO dto = captor.getValue();
        assertEquals(List.of("user@example.com"), dto.getTo());
        assertEquals("Order confirmed", dto.getSubject());
        assertEquals("<h1>Thanks</h1>", dto.getHtmlBody());
        // priority "high" is upper-cased before MailPriority.valueOf
        assertEquals(MailPriority.HIGH, dto.getPriority());
    }

    @Test
    void templateSendBuildsOneTemplateMessage() {

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.SEND_EMAIL)
                .input(Map.of(
                        "to", List.of("a@example.com", "b@example.com"),
                        "templateCode", "ORDER_CONFIRMATION",
                        "templateVariables", Map.of("orderId", "O-1")))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of());

        assertEquals(Boolean.TRUE, result.get("sent"));
        assertEquals("EMAIL", result.get("channel"));
        assertEquals("ORDER_CONFIRMATION", result.get("templateCode"));
        assertEquals(List.of("a@example.com", "b@example.com"), result.get("to"));

        ArgumentCaptor<SendMailDTO> captor = ArgumentCaptor.forClass(SendMailDTO.class);
        verify(messageService).sendMail(captor.capture());
        assertEquals("ORDER_CONFIRMATION", captor.getValue().getTemplateCode());
        assertEquals(List.of("a@example.com", "b@example.com"), captor.getValue().getTo());
        assertEquals(Map.of("orderId", "O-1"), captor.getValue().getTemplateVariables());
    }

    @Test
    void missingRecipientsThrows() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.SEND_EMAIL)
                .input(Map.of("subject", "No recipients"))
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(request, Map.of()));
        assertTrue(ex.getMessage().contains("at least one recipient"));
        verifyNoInteractions(messageService);
    }

    @Test
    void blankRecipientEntriesAreFilteredOut() {
        // a list containing only blank strings resolves to an empty recipient list → throws
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.SEND_EMAIL)
                .input(Map.of("to", List.of("   ", "")))
                .build();

        assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
        verifyNoInteractions(messageService);
    }
}
