package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The top bar's counts for the current actor in one cheap read: what waits on them and their unread copies —
 * cheaper than probing the paged endpoints with {@code pageSize=1} for their totals — and their unread inbox
 * notifications, which the bell's badge shows.
 */
@Schema(name = "FlowInboxCountView")
public record FlowInboxCountView(

        @Schema(description = "Pending approval tasks assigned to the caller")
        long pendingApprovals,

        @Schema(description = "Unread CC copies assigned to the caller")
        long unreadCc,

        @Schema(description = "The caller's unread inbox notifications, as GET /InboxNotification/myCountUnread counts "
                + "them — the bell's badge. A new task comes with a notification, so it shows there without being "
                + "counted twice. 0 without message-starter's inbox")
        long unreadNotifications
) {

    /** The task counts alone; {@link #withUnreadNotifications} adds the notifications. */
    public FlowInboxCountView(long pendingApprovals, long unreadCc) {
        this(pendingApprovals, unreadCc, 0);
    }

    /** These counts with the caller's unread notifications. */
    public FlowInboxCountView withUnreadNotifications(long unread) {
        return new FlowInboxCountView(pendingApprovals, unreadCc, unread);
    }
}
