package io.softa.starter.flow.runtime.task.builtin;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.message.sms.dto.SendSmsDTO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link SendSmsTaskExecutor} — direct and template-based SMS sending,
 * plus input validation error paths.
 */
class SendSmsTaskExecutorTest {

    private MessageService messageService;
    private SendSmsTaskExecutor executor;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        executor = new SendSmsTaskExecutor(messageService);
    }

    @Test
    void reportsSupportedNodeType() {
        assertEquals(FlowNodeType.SEND_SMS, executor.getSupportedNodeType());
    }

    @Test
    void directSendForwardsContentAndPhoneNumbersToService() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.SEND_SMS)
                .input(Map.of(
                        "phoneNumbers", List.of("+1234567890"),
                        "content", "Your order has shipped."))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of());

        assertEquals(true, result.get("sent"));
        assertEquals("SMS", result.get("channel"));
        assertEquals(List.of("+1234567890"), result.get("phoneNumbers"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SendSmsDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageService).sendSmsBatch(captor.capture());
        SendSmsDTO dto = captor.getValue().getFirst();
        assertEquals("+1234567890", dto.getPhoneNumber());
        assertEquals("Your order has shipped.", dto.getContent());
    }

    @Test
    void templateSendForwardsTemplateCodeToService() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.SEND_SMS)
                .input(Map.of(
                        "phoneNumbers", List.of("+1234567890"),
                        "templateCode", "VERIFY_CODE",
                        "templateVariables", Map.of("code", "123456")))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of());

        assertEquals(true, result.get("sent"));
        assertEquals("SMS", result.get("channel"));
        assertEquals("VERIFY_CODE", result.get("templateCode"));
        assertEquals(List.of("+1234567890"), result.get("phoneNumbers"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SendSmsDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageService).sendSmsBatch(captor.capture());
        SendSmsDTO dto = captor.getValue().getFirst();
        assertEquals("+1234567890", dto.getPhoneNumber());
        assertEquals("VERIFY_CODE", dto.getTemplateCode());
        assertEquals(Map.of("code", "123456"), dto.getTemplateVariables());
    }

    @Test
    void missingPhoneNumbersThrows() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.SEND_SMS)
                .input(Map.of("content", "Hello"))
                .build();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(request, Map.of()));
        assertTrue(ex.getMessage().contains("phoneNumber"));
    }

    @Test
    void missingContentAndTemplateThrows() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.SEND_SMS)
                .input(Map.of("phoneNumbers", List.of("+1234567890")))
                .build();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(request, Map.of()));
        assertTrue(ex.getMessage().contains("content") || ex.getMessage().contains("templateCode"));
    }
}
