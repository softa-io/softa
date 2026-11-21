package io.softa.starter.message.push.queue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.starter.message.inbox.enums.NotificationType;
import io.softa.starter.message.inbox.event.InboxNotificationsCreated;
import io.softa.starter.message.inbox.event.InboxNotificationsCreated.Created;
import io.softa.starter.message.push.PushSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Only what the sender asked to push is queued, carrying what the push says. */
class AppPushDispatcherTest {

    private final PushSender sender = mock(PushSender.class);
    private final AppPushQueue queue = mock(AppPushQueue.class);
    private final AppPushDispatcher dispatcher = new AppPushDispatcher(sender, queue);

    @BeforeEach
    void pushIsOn() {
        when(sender.anyAvailable()).thenReturn(true);
        when(queue.available()).thenReturn(true);
    }

    private static Created item(Long id, boolean push) {
        return new Created(id, 5L, NotificationType.WORKFLOW, "FlowInstance", "900", 10L, "Title " + id,
                "Content " + id, push);
    }

    @Test
    void theMessagesAskedForAreQueued_withWhatThePushSays() {
        dispatcher.dispatch(new InboxNotificationsCreated(List.of(item(81L, true), item(82L, false), item(83L, true))));

        ArgumentCaptor<AppPushMessage> queued = ArgumentCaptor.forClass(AppPushMessage.class);
        verify(queue, org.mockito.Mockito.times(2)).publish(queued.capture());
        assertThat(queued.getAllValues()).extracting(AppPushMessage::notificationId).containsExactly(81L, 83L);
        AppPushMessage first = queued.getAllValues().getFirst();
        assertThat(first.tenantId()).isEqualTo(5L);
        assertThat(first.recipientId()).isEqualTo(10L);
        assertThat(first.title()).isEqualTo("Title 81");
        assertThat(first.content()).isEqualTo("Content 81");
        assertThat(first.sourceModel()).isEqualTo("FlowInstance");
        assertThat(first.sourceId()).isEqualTo("900");
    }

    @Test
    void oneWithoutATenantOrRecipientIsNotQueued() {
        dispatcher.dispatch(new InboxNotificationsCreated(List.of(
                new Created(81L, null, NotificationType.SYSTEM, null, null, 10L, "t", "c", true),
                new Created(82L, 5L, NotificationType.SYSTEM, null, null, null, "t", "c", true))));

        verify(queue, never()).publish(any());
    }

    @Test
    void nothingIsQueued_withoutCredentialsOrWithoutATopic() {
        when(sender.anyAvailable()).thenReturn(false);
        dispatcher.dispatch(new InboxNotificationsCreated(List.of(item(81L, true))));

        when(sender.anyAvailable()).thenReturn(true);
        when(queue.available()).thenReturn(false);
        dispatcher.dispatch(new InboxNotificationsCreated(List.of(item(81L, true))));

        verify(queue, never()).publish(any());
    }

    @Test
    void aFailureNeverReachesTheWriter() {
        doThrow(new IllegalStateException("broker down")).when(queue).publish(any());

        assertThatCode(() -> dispatcher.onInboxWritten(new InboxNotificationsCreated(List.of(item(81L, true)))))
                .doesNotThrowAnyException();
    }
}
