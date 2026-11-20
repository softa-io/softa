package io.softa.starter.message.inbox.event;

import java.util.List;

import io.softa.starter.message.inbox.enums.NotificationType;

/**
 * Inbox notifications were written. Published inside the transaction that wrote them, so a listener that must only
 * act on committed messages uses {@code @TransactionalEventListener(AFTER_COMMIT)}: rolled back, nothing is heard.
 * An application uses this to carry a message further, such as to a phone, without the sender knowing.
 *
 * <p>Each item carries what a listener needs to act without reading the notification back: who it is for, what it
 * says, what it refers to, and whether its sender asked for a push.
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
     * @param sourceId    the id of the object it refers to, as text; may be null
     * @param recipientId the account it is for
     * @param title       its title
     * @param content     its content
     * @param push        whether its sender asked for it to be pushed to the recipient's devices
     */
    public record Created(Long id, Long tenantId, NotificationType type, String sourceModel, String sourceId,
                          Long recipientId, String title, String content, boolean push) {
    }
}
