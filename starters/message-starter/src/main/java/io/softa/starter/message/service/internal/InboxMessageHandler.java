package io.softa.starter.message.service.internal;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.inbox.entity.InboxNotification;
import io.softa.starter.message.inbox.enums.NotificationType;
import io.softa.starter.message.inbox.service.InboxNotificationService;

/** Internal persistence handler for inbox submissions. */
@Component
@RequiredArgsConstructor
final class InboxMessageHandler {

    private final InboxNotificationService notificationService;

    Long send(SendInboxDTO message) {
        validate(message);
        return notificationService.createOne(toNotification(message));
    }

    List<Long> sendBatch(List<SendInboxDTO> messages) {
        messages.forEach(InboxMessageHandler::validate);
        return notificationService.createList(messages.stream()
                .map(InboxMessageHandler::toNotification)
                .toList());
    }

    private static void validate(SendInboxDTO message) {
        if (message.getRecipientId() == null) {
            throw new BusinessException("Inbox send rejected: recipientId is required");
        }
        if (!StringUtils.hasText(message.getTitle())) {
            throw new BusinessException("Inbox send rejected: title is required");
        }
        if (message.getTitle().length() > 200) {
            throw new BusinessException("Inbox send rejected: title exceeds 200 characters");
        }
        if (!StringUtils.hasText(message.getContent())) {
            throw new BusinessException("Inbox send rejected: content is required");
        }
        if (message.getSourceModel() != null && message.getSourceModel().length() > 50) {
            throw new BusinessException("Inbox send rejected: sourceModel exceeds 50 characters");
        }
    }

    private static InboxNotification toNotification(SendInboxDTO message) {
        InboxNotification notification = new InboxNotification();
        notification.setRecipientId(message.getRecipientId());
        notification.setTitle(message.getTitle());
        notification.setContent(message.getContent());
        notification.setNotificationType(message.getNotificationType() != null
                ? message.getNotificationType() : NotificationType.SYSTEM);
        notification.setSourceModel(message.getSourceModel());
        notification.setSourceId(message.getSourceId());
        notification.setExpiredAt(message.getExpiredAt());
        notification.setIsRead(false);
        return notification;
    }
}
