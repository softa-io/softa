package io.softa.starter.message.inbox.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.inbox.entity.InboxNotification;

/**
 * CRUD service for {@link InboxNotification}.
 * <p>
 * Low-level service — prefer {@link InboxService} for application use.
 */
public interface InboxNotificationService extends EntityService<InboxNotification, Long> {

    /**
     * Mark a single notification as read and record the timestamp.
     *
     * @param id notification ID
     */
    void markAsRead(Long id);

    /**
     * Mark all unread notifications for a recipient as read.
     *
     * @param recipientId user ID
     */
    void markAllAsRead(Long recipientId);

    /**
     * Count unread notifications for a recipient.
     *
     * @param recipientId user ID
     * @return count of unread notifications
     */
    int countUnread(Long recipientId);
}
