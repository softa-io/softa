package io.softa.starter.message.inbox.controller;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.starter.message.inbox.service.InboxNotificationService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The notification bell must not be subject to row scope.
 *
 * <p>{@code InboxNotification} is anchorless — nothing a business role is granted references it — so
 * {@code PermissionServiceImpl.scopeWithoutGrant} finds no referencer and fails closed to
 * {@code matchNone()}. Every {@code my*} endpoint already pins its rows to {@code requireUserId()},
 * so the check cannot narrow anything; it can only empty the result.
 *
 * <p><b>Why this is worth pinning</b>: it empties silently. The bell reports zero unread and an empty
 * list, which reads as "no notifications" rather than as a failure — the same defect on
 * {@code UserProfile} announced itself as a 400 and was fixed within the hour, while this one can sit
 * in a release indefinitely. Nobody files a bug against a quiet bell.
 *
 * <p>The waiver belongs on the controller, not on {@link InboxNotificationService}: this is the layer
 * that takes the id from the request context. The service methods take {@code recipientId} as a
 * parameter, and the second test keeps them checked — waiving those would hand whatever passes an id
 * next somebody else's inbox.
 */
class InboxSelfServiceScopeWaiverTest {

    @Test
    @DisplayName("every my* endpoint waives row scope")
    void selfServiceEndpointsWaiveRowScope() throws Exception {
        assertWaived("myCountUnread");
        assertWaived("myRecent", int.class);
        assertWaived("myMarkAsRead", Long.class);
        assertWaived("myMarkAllAsRead");
        assertWaived("myMarkAsReadBySource", String.class, String.class);
    }

    @Test
    @DisplayName("the recipientId-taking service methods stay checked")
    void parameterisedServiceMethodsStayChecked() throws Exception {
        for (String name : new String[] {"markAllAsRead", "countUnread"}) {
            Method m = InboxNotificationService.class.getDeclaredMethod(name, Long.class);
            assertNull(m.getAnnotation(SkipPermissionCheck.class),
                    "InboxNotificationService." + name + " takes recipientId as a parameter; the waiver "
                            + "belongs on the controller's my* entry point, where the id comes from the "
                            + "request context and the bound is visible.");
        }
    }

    private static void assertWaived(String name, Class<?>... params) throws Exception {
        Method m = InboxNotificationController.class.getDeclaredMethod(name, params);
        assertNotNull(m.getAnnotation(SkipPermissionCheck.class),
                "InboxNotificationController." + name + " pins its rows to requireUserId() and must carry "
                        + "@SkipPermissionCheck — InboxNotification is anchorless, so row scope fails closed "
                        + "and the bell goes silently empty. See the block comment above the endpoints.");
    }
}
