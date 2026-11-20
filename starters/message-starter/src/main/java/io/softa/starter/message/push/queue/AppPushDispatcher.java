package io.softa.starter.message.push.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.message.inbox.event.InboxNotificationsCreated;
import io.softa.starter.message.push.PushSender;

/**
 * Queues the inbox messages whose sender asked for a push ({@code SendInboxDTO.push}), once they are committed: rolled
 * back, nothing is pushed. Off while no platform has credentials or no topic is set, so nothing piles up unsent.
 */
@Slf4j
@Component
public class AppPushDispatcher {

    private final PushSender pushSender;
    private final AppPushQueue queue;

    public AppPushDispatcher(PushSender pushSender, AppPushQueue queue) {
        this.pushSender = pushSender;
        this.queue = queue;
    }

    /**
     * After the inbox commits, and when it is written outside a transaction too. Never fails the writer: it has
     * committed already, and a push lost leaves the inbox message where it is.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInboxWritten(InboxNotificationsCreated event) {
        try {
            dispatch(event);
        } catch (RuntimeException e) {
            log.error("Push: {} inbox message(s) not queued: {}", event.items().size(), e.toString());
        }
    }

    void dispatch(InboxNotificationsCreated event) {
        if (!pushSender.anyAvailable() || !queue.available()) {
            return;
        }
        String traceId = ContextHolder.existContext() ? ContextHolder.getContext().getTraceId() : null;
        for (InboxNotificationsCreated.Created item : event.items()) {
            if (item.push() && item.id() != null && item.tenantId() != null && item.recipientId() != null) {
                queue.publish(new AppPushMessage(item.id(), item.tenantId(), item.recipientId(), item.title(),
                        item.content(), item.sourceModel(), item.sourceId(), traceId));
            }
        }
    }
}
