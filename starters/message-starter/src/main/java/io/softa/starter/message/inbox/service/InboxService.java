package io.softa.starter.message.inbox.service;

import io.softa.starter.message.inbox.entity.InboxTodo;
import io.softa.starter.message.inbox.enums.NotificationType;

import java.util.List;

/**
 * Public facade for the inbox system — notifications and todos.
 * <p>
 * Inject this interface wherever the application needs to push notifications or
 * create actionable todo items. The implementation automatically routes to the
 * appropriate internal services.
 * <p>
 * Integration point for {@code flow-starter}: call {@link #createTodo} from workflow
 * approval/review nodes to generate inbox tasks for the designated assignees.
 *
 * <pre>{@code
 * @Autowired
 * private InboxService inboxService;
 *
 * // Push a notification
 * inboxService.notify(userId, "Order shipped", "Your order #1234 has been dispatched.");
 *
 * // Create a todo from a workflow node
 * inboxService.createTodo(approverId, "Approve leave request",
 *     "Employee Alice has requested 3 days of annual leave.",
 *     "FLOW_INSTANCE", flowInstanceId, "/flow/approval/" + flowInstanceId);
 * }</pre>
 */
public interface InboxService {

    // ── Notifications ─────────────────────────────────────────────────────────

    /**
     * Push a SYSTEM notification to a single recipient.
     */
    void notify(Long recipientId, String title, String content);

    /**
     * Push a SYSTEM notification to multiple recipients.
     */
    void notify(List<Long> recipientIds, String title, String content);

    /**
     * Push a typed notification to multiple recipients.
     */
    void notify(List<Long> recipientIds, String title, String content, NotificationType type);

    /**
     * Push a typed notification to a single recipient with source reference.
     */
    void notify(Long recipientId, String title, String content,
                NotificationType type, String sourceType, Long sourceId);

    /**
     * Count unread notifications for a user.
     */
    int countUnread(Long recipientId);

    /**
     * Mark a single notification as read.
     *
     * @param notificationId notification ID
     */
    void markAsRead(Long notificationId);

    /**
     * Mark all unread notifications for a user as read.
     *
     * @param recipientId user ID
     */
    void markAllAsRead(Long recipientId);

    // ── Todos ─────────────────────────────────────────────────────────────────

    /**
     * Create a todo item assigned to a user.
     *
     * @param assigneeId  user ID of the person who must act
     * @param title       short description shown in the todo list
     * @param description detailed explanation of the required action
     * @param sourceType  originating object type, e.g. {@code "FLOW_INSTANCE"} (nullable)
     * @param sourceId    originating object ID (nullable)
     * @param actionUrl   deep-link URL to the handling page (nullable)
     * @return the created todo
     */
    InboxTodo createTodo(Long assigneeId, String title, String description,
                         String sourceType, Long sourceId, String actionUrl);

    /**
     * Batch create todo items for multiple assignees with identical content.
     *
     * @param assigneeIds list of user IDs
     * @param title       short description
     * @param description detailed explanation
     * @param sourceType  originating object type (nullable)
     * @param sourceId    originating object ID (nullable)
     * @param actionUrl   deep-link URL (nullable)
     * @return list of created todos
     */
    List<InboxTodo> createTodos(List<Long> assigneeIds, String title, String description,
                                String sourceType, Long sourceId, String actionUrl);

    /**
     * Mark a todo as done.
     *
     * @param todoId todo ID
     */
    void completeTodo(Long todoId);

    /**
     * Mark a todo as rejected.
     *
     * @param todoId todo ID
     */
    void rejectTodo(Long todoId);

    /**
     * Cancel a single todo.
     *
     * @param todoId todo ID
     */
    void cancelTodo(Long todoId);

    /**
     * Cancel all pending todos linked to a specific source object.
     * Typically used when a flow instance is revoked or cancelled.
     *
     * @param sourceType originating object type (e.g. "FLOW_INSTANCE")
     * @param sourceId   originating object ID
     * @return number of todos cancelled
     */
    int cancelTodosBySource(String sourceType, Long sourceId);

    /**
     * Find all todos linked to a specific source object.
     *
     * @param sourceType originating object type
     * @param sourceId   originating object ID
     * @return list of matching todos
     */
    List<InboxTodo> findTodosBySource(String sourceType, Long sourceId);

    /**
     * Count pending todos for a user.
     *
     * @param assigneeId user ID
     * @return count of todos in PENDING status
     */
    int countPendingTodos(Long assigneeId);
}
