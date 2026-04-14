package io.softa.starter.message.inbox.service.impl;

import io.softa.starter.message.inbox.entity.InboxNotification;
import io.softa.starter.message.inbox.entity.InboxTodo;
import io.softa.starter.message.inbox.enums.NotificationType;
import io.softa.starter.message.inbox.enums.TodoStatus;
import io.softa.starter.message.inbox.service.InboxNotificationService;
import io.softa.starter.message.inbox.service.InboxService;
import io.softa.starter.message.inbox.service.InboxTodoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of {@link InboxService}.
 * <p>
 * Delegates to {@link InboxNotificationService} and {@link InboxTodoService}.
 */
@Service
public class InboxServiceImpl implements InboxService {

    @Autowired
    private InboxNotificationService notificationService;

    @Autowired
    private InboxTodoService todoService;

    // ── Notifications ─────────────────────────────────────────────────────────

    @Override
    public void notify(Long recipientId, String title, String content) {
        notify(recipientId, title, content, NotificationType.SYSTEM, null, null);
    }

    @Override
    public void notify(List<Long> recipientIds, String title, String content) {
        notify(recipientIds, title, content, NotificationType.SYSTEM);
    }

    @Override
    public void notify(List<Long> recipientIds, String title, String content, NotificationType type) {
        List<InboxNotification> notifications = recipientIds.stream().map(recipientId -> {
            InboxNotification n = new InboxNotification();
            n.setRecipientId(recipientId);
            n.setTitle(title);
            n.setContent(content);
            n.setNotificationType(type);
            n.setIsRead(false);
            return n;
        }).toList();
        notificationService.createList(notifications);
    }

    @Override
    public void notify(Long recipientId, String title, String content,
                        NotificationType type, String sourceType, Long sourceId) {
        InboxNotification notification = new InboxNotification();
        notification.setRecipientId(recipientId);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setNotificationType(type);
        notification.setSourceType(sourceType);
        notification.setSourceId(sourceId);
        notification.setIsRead(false);
        notificationService.createOne(notification);
    }

    @Override
    public int countUnread(Long recipientId) {
        return notificationService.countUnread(recipientId);
    }

    @Override
    public void markAsRead(Long notificationId) {
        notificationService.markAsRead(notificationId);
    }

    @Override
    public void markAllAsRead(Long recipientId) {
        notificationService.markAllAsRead(recipientId);
    }

    // ── Todos ─────────────────────────────────────────────────────────────────

    @Override
    public InboxTodo createTodo(Long assigneeId, String title, String description,
                                String sourceType, Long sourceId, String actionUrl) {
        InboxTodo todo = new InboxTodo();
        todo.setAssigneeId(assigneeId);
        todo.setTitle(title);
        todo.setDescription(description);
        todo.setSourceType(sourceType);
        todo.setSourceId(sourceId);
        todo.setActionUrl(actionUrl);
        todo.setStatus(TodoStatus.PENDING);
        return todoService.createOneAndFetch(todo);
    }

    @Override
    public List<InboxTodo> createTodos(List<Long> assigneeIds, String title, String description,
                                       String sourceType, Long sourceId, String actionUrl) {
        List<InboxTodo> todos = new ArrayList<>(assigneeIds.size());
        for (Long assigneeId : assigneeIds) {
            InboxTodo todo = new InboxTodo();
            todo.setAssigneeId(assigneeId);
            todo.setTitle(title);
            todo.setDescription(description);
            todo.setSourceType(sourceType);
            todo.setSourceId(sourceId);
            todo.setActionUrl(actionUrl);
            todo.setStatus(TodoStatus.PENDING);
            todos.add(todo);
        }
        todoService.createList(todos);
        return todos;
    }

    @Override
    public void completeTodo(Long todoId) {
        todoService.complete(todoId);
    }

    @Override
    public void rejectTodo(Long todoId) {
        todoService.reject(todoId);
    }

    @Override
    public void cancelTodo(Long todoId) {
        todoService.cancel(todoId);
    }

    @Override
    public int cancelTodosBySource(String sourceType, Long sourceId) {
        return todoService.cancelBySource(sourceType, sourceId);
    }

    @Override
    public List<InboxTodo> findTodosBySource(String sourceType, Long sourceId) {
        return todoService.findBySource(sourceType, sourceId);
    }

    @Override
    public int countPendingTodos(Long assigneeId) {
        return todoService.countPending(assigneeId);
    }
}
