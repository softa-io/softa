package io.softa.starter.flow.runtime.task.builtin;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;
import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.inbox.enums.NotificationType;
import io.softa.starter.message.service.MessageService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link SendInboxNotificationTaskExecutor}.
 */
class SendInboxNotificationTaskExecutorTest {

    private MessageService messageService;
    private SendInboxNotificationTaskExecutor executor;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        executor = new SendInboxNotificationTaskExecutor(messageService);
    }

    private TaskExecutionRequest request(Map<String, Object> input) {
        return TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.SEND_INBOX_NOTIFICATION)
                .input(input)
                .build();
    }

    @Test
    void sendsNotificationAndReturnsSummary() {
        TaskExecutionRequest req = request(Map.of(
                "recipientIds", List.of(1L, 2L),
                "title", "New approval request",
                "content", "Please review."));

        Map<String, Object> result = executor.execute(req, Map.of());

        assertEquals(Boolean.TRUE, result.get("sent"));
        assertEquals("INBOX", result.get("channel"));
        assertEquals(2, result.get("recipientCount"));
        // Default notificationType is WORKFLOW when not supplied.
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<SendInboxDTO>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(messageService).sendInboxBatch(captor.capture());
        assertEquals(List.of(1L, 2L), captor.getValue().stream()
                .map(SendInboxDTO::getRecipientId)
                .toList());
        assertEquals(NotificationType.WORKFLOW, captor.getValue().getFirst().getNotificationType());
    }

    @Test
    void interpolatesAndHonorsExplicitNotificationType() {
        TaskExecutionRequest req = request(Map.of(
                "recipientIds", List.of(100L),
                "title", "Order {{ orderId }}",
                "content", "needs attention",
                "notificationType", "system"));

        Map<String, Object> result = executor.execute(req, Map.of("orderId", "O-7"));

        assertEquals(1, result.get("recipientCount"));
        // {{ orderId }} resolved from variables; lower-case type upper-cased to SYSTEM.
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<SendInboxDTO>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(messageService).sendInboxBatch(captor.capture());
        SendInboxDTO message = captor.getValue().getFirst();
        assertEquals(100L, message.getRecipientId());
        assertEquals("Order O-7", message.getTitle());
        assertEquals("needs attention", message.getContent());
        assertEquals(NotificationType.SYSTEM, message.getNotificationType());
    }

    @Test
    void throwsWhenNoRecipients() {
        TaskExecutionRequest req = request(Map.of(
                "recipientIds", List.of(),
                "title", "t",
                "content", "c"));

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> executor.execute(req, Map.of()));
        assertTrue(ex.getMessage().contains("recipientIds"));
    }

    @Test
    void throwsWhenTitleMissing() {
        TaskExecutionRequest req = request(Map.of(
                "recipientIds", List.of(1L),
                "content", "c"));

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> executor.execute(req, Map.of()));
        assertTrue(ex.getMessage().contains("title"));
    }

    @Test
    void throwsWhenContentBlank() {
        TaskExecutionRequest req = request(Map.of(
                "recipientIds", List.of(1L),
                "title", "t",
                "content", "   "));

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> executor.execute(req, Map.of()));
        assertTrue(ex.getMessage().contains("content"));
    }
}
