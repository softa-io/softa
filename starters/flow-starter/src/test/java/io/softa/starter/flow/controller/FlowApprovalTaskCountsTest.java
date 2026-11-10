package io.softa.starter.flow.controller;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.dto.FlowInboxCountView;
import io.softa.starter.flow.service.FlowApprovalTaskQueryService;
import io.softa.starter.message.inbox.service.InboxNotificationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The top bar reads its counts in one request: the caller's tasks, and their unread notifications for the bell's
 * badge — none to count without message-starter's inbox.
 */
class FlowApprovalTaskCountsTest {

    private final FlowApprovalTaskQueryService tasks = mock(FlowApprovalTaskQueryService.class);
    private final InboxNotificationService inbox = mock(InboxNotificationService.class);

    @Test
    void theCountsCarryTheCallersUnreadNotifications() {
        when(tasks.countInbox("7")).thenReturn(new FlowInboxCountView(3, 2));
        when(inbox.countUnread(7L)).thenReturn(5);

        FlowInboxCountView counts = as(7L, () -> controller(inbox).counts().getData());

        assertEquals(new FlowInboxCountView(3, 2, 5), counts);
    }

    @Test
    void withoutTheInboxThereAreNoNotificationsToCount() {
        when(tasks.countInbox("7")).thenReturn(new FlowInboxCountView(3, 2));

        FlowInboxCountView counts = as(7L, () -> controller(null).counts().getData());

        assertEquals(new FlowInboxCountView(3, 2, 0), counts);
    }

    private FlowApprovalTaskController controller(InboxNotificationService available) {
        @SuppressWarnings("unchecked")
        ObjectProvider<InboxNotificationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        return new FlowApprovalTaskController(tasks, provider);
    }

    private static <T> T as(Long userId, Supplier<T> call) {
        Context context = new Context();
        context.setUserId(userId);
        return ContextHolder.callWith(context, call::get);
    }
}
