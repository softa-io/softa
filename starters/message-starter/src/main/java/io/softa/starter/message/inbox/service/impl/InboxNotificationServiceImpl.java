package io.softa.starter.message.inbox.service.impl;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.inbox.entity.InboxNotification;
import io.softa.starter.message.inbox.service.InboxNotificationService;

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
    public int markReadBySource(Long recipientId, String sourceModel, String sourceId) {
        Filters unread = new Filters()
                .eq(InboxNotification::getRecipientId, recipientId)
                .eq(InboxNotification::getSourceModel, sourceModel)
                .eq(InboxNotification::getSourceId, sourceId)
                .eq(InboxNotification::getIsRead, false);
        InboxNotification patch = new InboxNotification();
        patch.setIsRead(true);
        patch.setReadAt(LocalDateTime.now());
        return updateByFilter(unread, patch);
    }

    @Override
    public int markReadByIds(Long recipientId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Filters unread = new Filters()
                .eq(InboxNotification::getRecipientId, recipientId)
                .in(InboxNotification::getId, List.copyOf(ids))
                .eq(InboxNotification::getIsRead, false);
        InboxNotification patch = new InboxNotification();
        patch.setIsRead(true);
        patch.setReadAt(LocalDateTime.now());
        return updateByFilter(unread, patch);
    }

    @Override
    public int countUnread(Long recipientId) {
        return (int) count(new Filters()
                .eq(InboxNotification::getRecipientId, recipientId)
                .eq(InboxNotification::getIsRead, false));
    }
}
