package io.softa.starter.message.inbox.service.impl;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.inbox.entity.InboxNotification;
import io.softa.starter.message.inbox.service.InboxNotificationService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Implementation of {@link InboxNotificationService}.
 */
@Service
public class InboxNotificationServiceImpl extends EntityServiceImpl<InboxNotification, Long>
        implements InboxNotificationService {

    @Override
    public void markAsRead(Long id) {
        InboxNotification notification = getById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notification {0} not found.", id));
        if (Boolean.TRUE.equals(notification.getIsRead())) {
            return;
        }
        notification.setIsRead(true);
        notification.setReadAt(LocalDateTime.now());
        updateOne(notification);
    }

    @Override
    public void markAllAsRead(Long recipientId) {
        Filters unread = new Filters()
                .eq(InboxNotification::getRecipientId, recipientId)
                .eq(InboxNotification::getIsRead, false);
        InboxNotification patch = new InboxNotification();
        patch.setIsRead(true);
        patch.setReadAt(LocalDateTime.now());
        updateByFilter(unread, patch);
    }

    @Override
    public int countUnread(Long recipientId) {
        return (int) count(new Filters()
                .eq(InboxNotification::getRecipientId, recipientId)
                .eq(InboxNotification::getIsRead, false));
    }
}
