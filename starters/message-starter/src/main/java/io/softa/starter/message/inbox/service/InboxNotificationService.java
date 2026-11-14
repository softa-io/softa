package io.softa.starter.message.inbox.service;

import java.util.Collection;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.inbox.entity.InboxNotification;

/**
 * CRUD service for {@link InboxNotification}.
 * <p>
 * Low-level persistence/query service — prefer
 * {@link io.softa.starter.message.service.MessageService} for submissions.
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
     * Mark a recipient's unread notifications about one source object as read — once they have dealt with the
     * object, what told them about it is old news (an approver who acted on an approval, every reminder of it).
     *
     * @param recipientId user ID
     * @param sourceModel the source's model name, e.g. {@code FlowInstance}
     * @param sourceId    the source object's id as text
     * @return how many were marked
     */
    int markReadBySource(Long recipientId, String sourceModel, String sourceId);

    /**
     * Mark a recipient's unread notifications among the ids given as read — the ones picked on a list of them. An id
     * of another recipient's notification is passed over.
     *
     * @param recipientId user ID
     * @param ids         notification IDs; null or empty marks nothing
     * @return how many were marked
     */
    int markReadByIds(Long recipientId, Collection<Long> ids);

    /**
     * Count unread notifications for a recipient.
     *
     * @param recipientId user ID
     * @return count of unread notifications
     */
    int countUnread(Long recipientId);
}
