package io.softa.starter.message.inbox.service.impl;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.message.inbox.entity.InboxNotification;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class InboxNotificationServiceImplTest {

    private InboxNotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new InboxNotificationServiceImpl());
    }

    // ========== markAsRead Tests ==========

    @Test
    void markAsReadSetsIsReadAndReadAt() {
        InboxNotification notification = new InboxNotification();
        notification.setId(1L);
        notification.setIsRead(false);

        doReturn(Optional.of(notification)).when(service).getById(1L);
        doReturn(true).when(service).updateOne(any());

        service.markAsRead(1L);

        Assertions.assertTrue(notification.getIsRead());
        Assertions.assertNotNull(notification.getReadAt());
        verify(service).updateOne(notification);
    }

    @Test
    void markAsReadSkipsAlreadyReadNotification() {
        InboxNotification notification = new InboxNotification();
        notification.setId(2L);
        notification.setIsRead(true);

        doReturn(Optional.of(notification)).when(service).getById(2L);

        service.markAsRead(2L);

        verify(service, never()).updateOne(any());
    }

    @Test
    void markAsReadThrowsForNonexistentNotification() {
        doReturn(Optional.empty()).when(service).getById(99L);

        Assertions.assertThrows(Exception.class, () -> service.markAsRead(99L));
        verify(service, never()).updateOne(any());
    }

    // ========== markAllAsRead Tests ==========

    @Test
    void markAllAsReadCallsUpdateByFilterWithUnreadCondition() {
        doReturn(0).when(service).updateByFilter(any(Filters.class), any(InboxNotification.class));

        service.markAllAsRead(42L);

        verify(service).updateByFilter(any(Filters.class), any(InboxNotification.class));
    }

    @Test
    void markAllAsReadPatchSetsIsReadTrueAndReadAt() {
        var patchCaptor = org.mockito.ArgumentCaptor.forClass(InboxNotification.class);
        doReturn(0).when(service).updateByFilter(any(Filters.class), patchCaptor.capture());

        service.markAllAsRead(42L);

        InboxNotification patch = patchCaptor.getValue();
        Assertions.assertTrue(patch.getIsRead());
        Assertions.assertNotNull(patch.getReadAt());
    }

    // ========== countUnread Tests ==========

    @Test
    void countUnreadReturnsResultFromCount() {
        doReturn(7L).when(service).count(any(Filters.class));

        int result = service.countUnread(10L);

        Assertions.assertEquals(7, result);
    }

    @Test
    void countUnreadReturnsZeroWhenNoneUnread() {
        doReturn(0L).when(service).count(any(Filters.class));

        int result = service.countUnread(10L);

        Assertions.assertEquals(0, result);
    }
}
