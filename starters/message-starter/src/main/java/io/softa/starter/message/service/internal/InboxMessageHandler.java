package io.softa.starter.message.service.internal;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.inbox.entity.InboxNotification;
import io.softa.starter.message.inbox.enums.NotificationType;
import io.softa.starter.message.inbox.event.InboxNotificationsCreated;
import io.softa.starter.message.inbox.service.InboxNotificationService;

/**
 * Internal persistence handler for inbox submissions. A title longer than its column is cut to fit, ending in an
 * ellipsis, rather than refused: senders tell users past the action that sends, where a refusal is only logged, and
 * the message was lost for a long title (an automation's, an alert naming a long request). The content has no width.
 */
@Component
@RequiredArgsConstructor
final class InboxMessageHandler {

    /** The title column's width ({@code InboxNotification.title}). */
    static final int TITLE_MAX_LENGTH = 200;

    private final InboxNotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    Long send(SendInboxDTO message) {
        validate(message);
        Long id = notificationService.createOne(toNotification(message));
        publish(List.of(message), List.of(id));
        return id;
    }

    List<Long> sendBatch(List<SendInboxDTO> messages) {
        messages.forEach(InboxMessageHandler::validate);
        List<Long> ids = notificationService.createList(messages.stream()
                .map(InboxMessageHandler::toNotification)
                .toList());
        publish(messages, ids);
        return ids;
    }

    /** Ids come back in submission order, so each message pairs with the id at its position. */
    private void publish(List<SendInboxDTO> messages, List<Long> ids) {
        Long tenantId = ContextHolder.existContext() ? ContextHolder.getContext().getTenantId() : null;
        List<InboxNotificationsCreated.Created> items = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            SendInboxDTO message = messages.get(i);
            items.add(new InboxNotificationsCreated.Created(ids.get(i), tenantId,
                    message.getNotificationType() != null ? message.getNotificationType() : NotificationType.SYSTEM,
                    message.getSourceModel()));
        }
        eventPublisher.publishEvent(new InboxNotificationsCreated(List.copyOf(items)));
    }

    private static void validate(SendInboxDTO message) {
        if (message.getRecipientId() == null) {
            throw new BusinessException("Inbox send rejected: recipientId is required");
        }
        if (!StringUtils.hasText(message.getTitle())) {
            throw new BusinessException("Inbox send rejected: title is required");
        }
        if (!StringUtils.hasText(message.getContent())) {
            throw new BusinessException("Inbox send rejected: content is required");
        }
        if (message.getSourceModel() != null && message.getSourceModel().length() > 50) {
            throw new BusinessException("Inbox send rejected: sourceModel exceeds 50 characters");
        }
    }

    /** {@code title} cut to the column, its last character an ellipsis when it was longer. */
    static String fit(String title) {
        return title.length() <= TITLE_MAX_LENGTH ? title : title.substring(0, TITLE_MAX_LENGTH - 1) + "…";
    }

    private static InboxNotification toNotification(SendInboxDTO message) {
        InboxNotification notification = new InboxNotification();
        notification.setRecipientId(message.getRecipientId());
        notification.setTitle(fit(message.getTitle().strip()));
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
