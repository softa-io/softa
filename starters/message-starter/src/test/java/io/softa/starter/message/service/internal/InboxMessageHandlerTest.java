package io.softa.starter.message.service.internal;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.inbox.entity.InboxNotification;
import io.softa.starter.message.inbox.enums.NotificationType;
import io.softa.starter.message.inbox.event.InboxNotificationsCreated;
import io.softa.starter.message.inbox.service.InboxNotificationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InboxMessageHandlerTest {

    private InboxNotificationService notificationService;
    private ApplicationEventPublisher eventPublisher;
    private InboxMessageHandler handler;

    @BeforeEach
    void setUp() {
        notificationService = mock(InboxNotificationService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        handler = new InboxMessageHandler(notificationService, eventPublisher);
    }

    @Test
    void sendBatchPublishesEachNotificationWithItsOwnId() {
        when(notificationService.createList(any())).thenReturn(List.of(81L, 82L));
        SendInboxDTO first = message(10L, "First", "A");
        first.setNotificationType(NotificationType.WORKFLOW);
        first.setSourceModel("Order");
        first.setSourceId("9");

        Context context = new Context();
        context.setTenantId(5L);
        ContextHolder.runWith(context, () -> handler.sendBatch(List.of(first, message(20L, "Second", "B"))));

        ArgumentCaptor<InboxNotificationsCreated> captor = ArgumentCaptor.forClass(InboxNotificationsCreated.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(List.of(
                new InboxNotificationsCreated.Created(81L, 5L, NotificationType.WORKFLOW, "Order"),
                new InboxNotificationsCreated.Created(82L, 5L, NotificationType.SYSTEM, null)),
                captor.getValue().items());
    }

    @Test
    void aRejectedBatchPublishesNothing() {
        assertThrows(BusinessException.class, () -> handler.sendBatch(List.of(message(null, "T", "C"))));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void sendMapsAllSubmissionFields() {
        ArgumentCaptor<InboxNotification> captor = ArgumentCaptor.forClass(InboxNotification.class);
        when(notificationService.createOne(captor.capture())).thenReturn(71L);
        SendInboxDTO message = message(1L, "Hello", "World");
        message.setSourceModel("Order");
        message.setSourceId("9");

        Long id = handler.send(message);

        assertEquals(71L, id);
        InboxNotification created = captor.getValue();
        assertEquals(1L, created.getRecipientId());
        assertEquals("Hello", created.getTitle());
        assertEquals("World", created.getContent());
        assertEquals(NotificationType.SYSTEM, created.getNotificationType());
        assertEquals("Order", created.getSourceModel());
        assertEquals("9", created.getSourceId());
        assertFalse(created.getIsRead());
    }

    @Test
    void sendBatchCreatesOneNotificationPerMessageInOrder() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InboxNotification>> captor = ArgumentCaptor.forClass(List.class);
        when(notificationService.createList(captor.capture())).thenReturn(List.of(81L, 82L));

        List<Long> ids = handler.sendBatch(List.of(
                message(10L, "First", "A"),
                message(20L, "Second", "B")));

        assertEquals(List.of(81L, 82L), ids);
        assertEquals(List.of(10L, 20L), captor.getValue().stream()
                .map(InboxNotification::getRecipientId)
                .toList());
        verify(notificationService).createList(any());
    }

    @Test
    void invalidMessageIsRejectedBeforePersistence() {
        SendInboxDTO message = message(null, "", "World");

        assertThrows(BusinessException.class, () -> handler.send(message));
        verifyNoInteractions(notificationService);
    }

    @Test
    void invalidBatchItemIsRejectedBeforeBulkPersistence() {
        SendInboxDTO invalid = message(2L, "Title", " ");

        assertThrows(BusinessException.class, () -> handler.sendBatch(List.of(
                message(1L, "Valid", "Content"), invalid)));
        verifyNoInteractions(notificationService);
    }

    // zingkey-hcm#2602: an urge's 256-character note never reached the approver — the content column was 64 wide, and
    // the refusal was only logged by the flow that sent it. The content has no width now.
    @Test
    void longContentIsKeptWhole() {
        ArgumentCaptor<InboxNotification> captor = ArgumentCaptor.forClass(InboxNotification.class);
        when(notificationService.createOne(captor.capture())).thenReturn(72L);
        String note = "x".repeat(2000);

        handler.send(message(1L, "Reminder to approve: Overtime · Ann Lee", note));

        assertEquals(note, captor.getValue().getContent());
    }

    @Test
    void aTitleLongerThanItsColumnIsCutToFitNotRefused() {
        ArgumentCaptor<InboxNotification> captor = ArgumentCaptor.forClass(InboxNotification.class);
        when(notificationService.createOne(captor.capture())).thenReturn(73L);

        handler.send(message(1L, "A change could not be applied: " + "y".repeat(300), "Reason"));

        String title = captor.getValue().getTitle();
        assertEquals(InboxMessageHandler.TITLE_MAX_LENGTH, title.length());
        assertTrue(title.startsWith("A change could not be applied: y"));
        assertTrue(title.endsWith("…"));
    }

    @Test
    void aTitleThatFitsIsKeptAsSent() {
        String exactly = "t".repeat(InboxMessageHandler.TITLE_MAX_LENGTH);

        assertEquals(exactly, InboxMessageHandler.fit(exactly));
    }

    private static SendInboxDTO message(Long recipientId, String title, String content) {
        SendInboxDTO message = new SendInboxDTO();
        message.setRecipientId(recipientId);
        message.setTitle(title);
        message.setContent(content);
        return message;
    }
}
