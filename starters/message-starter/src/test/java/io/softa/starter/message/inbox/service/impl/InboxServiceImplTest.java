package io.softa.starter.message.inbox.service.impl;

import io.softa.starter.message.inbox.entity.InboxNotification;
import io.softa.starter.message.inbox.entity.InboxTodo;
import io.softa.starter.message.inbox.enums.NotificationType;
import io.softa.starter.message.inbox.enums.TodoStatus;
import io.softa.starter.message.inbox.service.InboxNotificationService;
import io.softa.starter.message.inbox.service.InboxTodoService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxServiceImplTest {

    private InboxServiceImpl service;
    private InboxNotificationService notificationService;
    private InboxTodoService todoService;

    @BeforeEach
    void setUp() {
        service = new InboxServiceImpl();
        notificationService = mock(InboxNotificationService.class);
        todoService = mock(InboxTodoService.class);

        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        ReflectionTestUtils.setField(service, "todoService", todoService);
    }

    // ========== notify (single recipient) Tests ==========

    @Test
    void notifySingleCreatesNotificationWithSystemTypeAndUnread() {
        ArgumentCaptor<InboxNotification> captor = ArgumentCaptor.forClass(InboxNotification.class);
        // createOne returns Long — mock returns null by default, which is fine
        when(notificationService.createOne(captor.capture())).thenReturn(null);

        service.notify(1L, "Hello", "World");

        InboxNotification created = captor.getValue();
        Assertions.assertEquals(1L, created.getRecipientId());
        Assertions.assertEquals("Hello", created.getTitle());
        Assertions.assertEquals("World", created.getContent());
        Assertions.assertEquals(NotificationType.SYSTEM, created.getNotificationType());
        Assertions.assertFalse(created.getIsRead());
    }

    @Test
    void notifySingleCallsCreateOne() {
        service.notify(5L, "Title", "Body");

        verify(notificationService).createOne(any(InboxNotification.class));
    }

    // ========== notify (batch recipients) Tests ==========

    @Test
    void notifyBatchCreatesOneNotificationPerRecipient() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InboxNotification>> captor = ArgumentCaptor.forClass(List.class);
        when(notificationService.createList(captor.capture())).thenReturn(null);

        service.notify(List.of(1L, 2L, 3L), "Batch", "Content");

        List<InboxNotification> created = captor.getValue();
        Assertions.assertEquals(3, created.size());
        Assertions.assertEquals(1L, created.get(0).getRecipientId());
        Assertions.assertEquals(2L, created.get(1).getRecipientId());
        Assertions.assertEquals(3L, created.get(2).getRecipientId());
    }

    @Test
    void notifyBatchSetsSystemTypeAndUnreadForAll() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InboxNotification>> captor = ArgumentCaptor.forClass(List.class);
        when(notificationService.createList(captor.capture())).thenReturn(null);

        service.notify(List.of(10L, 20L), "Msg", "Detail");

        captor.getValue().forEach(n -> {
            Assertions.assertEquals(NotificationType.SYSTEM, n.getNotificationType());
            Assertions.assertFalse(n.getIsRead());
        });
    }

    @Test
    void notifyBatchCallsCreateList() {
        service.notify(List.of(1L, 2L), "T", "C");

        verify(notificationService).createList(any());
    }

    // ========== countUnread Tests ==========

    @Test
    void countUnreadDelegatesToNotificationService() {
        when(notificationService.countUnread(7L)).thenReturn(4);

        int result = service.countUnread(7L);

        Assertions.assertEquals(4, result);
        verify(notificationService).countUnread(7L);
    }

    // ========== markAsRead / markAllAsRead Tests ==========

    @Test
    void markAsReadDelegatesToNotificationService() {
        doNothing().when(notificationService).markAsRead(11L);

        service.markAsRead(11L);

        verify(notificationService).markAsRead(11L);
    }

    @Test
    void markAllAsReadDelegatesToNotificationService() {
        doNothing().when(notificationService).markAllAsRead(22L);

        service.markAllAsRead(22L);

        verify(notificationService).markAllAsRead(22L);
    }

    // ========== createTodo Tests ==========

    @Test
    void createTodoCreatesWithPendingStatusAndCorrectFields() {
        InboxTodo persisted = new InboxTodo();
        persisted.setId(99L);
        persisted.setStatus(TodoStatus.PENDING);

        ArgumentCaptor<InboxTodo> captor = ArgumentCaptor.forClass(InboxTodo.class);
        when(todoService.createOneAndFetch(captor.capture())).thenReturn(persisted);

        InboxTodo result = service.createTodo(5L, "Fix it", "Description",
                "FLOW_INSTANCE", 123L, "/handle/123");

        InboxTodo submitted = captor.getValue();
        Assertions.assertEquals(5L, submitted.getAssigneeId());
        Assertions.assertEquals("Fix it", submitted.getTitle());
        Assertions.assertEquals("Description", submitted.getDescription());
        Assertions.assertEquals("FLOW_INSTANCE", submitted.getSourceType());
        Assertions.assertEquals(123L, submitted.getSourceId());
        Assertions.assertEquals("/handle/123", submitted.getActionUrl());
        Assertions.assertEquals(TodoStatus.PENDING, submitted.getStatus());
        Assertions.assertSame(persisted, result);
    }

    @Test
    void createTodoDelegatesToTodoServiceCreateOneAndFetch() {
        InboxTodo todo = new InboxTodo();
        when(todoService.createOneAndFetch(any())).thenReturn(todo);

        service.createTodo(1L, "T", "D", null, null, null);

        verify(todoService).createOneAndFetch(any(InboxTodo.class));
    }

    // ========== completeTodo / rejectTodo Tests ==========

    @Test
    void completeTodoDelegatesToTodoService() {
        doNothing().when(todoService).complete(33L);

        service.completeTodo(33L);

        verify(todoService).complete(33L);
    }

    @Test
    void rejectTodoDelegatesToTodoService() {
        doNothing().when(todoService).reject(44L);

        service.rejectTodo(44L);

        verify(todoService).reject(44L);
    }

    // ========== countPendingTodos Tests ==========

    @Test
    void countPendingTodosDelegatesToTodoService() {
        when(todoService.countPending(55L)).thenReturn(6);

        int result = service.countPendingTodos(55L);

        Assertions.assertEquals(6, result);
        verify(todoService).countPending(55L);
    }
}
