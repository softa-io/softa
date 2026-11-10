package io.softa.starter.message.inbox.event;

import java.util.List;

import io.softa.starter.message.inbox.enums.NotificationType;

/**
 * Inbox notifications were written. Published inside the transaction that wrote them, so a listener that must only
 * act on committed messages uses {@code @TransactionalEventListener(AFTER_COMMIT)}: rolled back, nothing is heard.
 * An application uses this to carry a message further, such as to a phone, without the sender knowing.
 *
 * <p>Each item carries only what a listener needs to decide whether to act. Who it is for and what it says are read
 * from the notification when acting on it, so a decision taken later, such as on a retry, sees what the row says then.
 *
 * @param items the notifications written, in the order they were submitted
 */
public record InboxNotificationsCreated(List<Created> items) {

    /**
     * One notification written.
     *
     * @param id          the notification id
     * @param tenantId    the tenant it belongs to; null when written outside a tenant context
     * @param type        its source category
     * @param sourceModel the model of the object it refers to; may be null
     */
    public record Created(Long id, Long tenantId, NotificationType type, String sourceModel) {
    }
}
