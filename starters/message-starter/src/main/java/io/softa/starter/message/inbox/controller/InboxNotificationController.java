package io.softa.starter.message.inbox.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.inbox.entity.InboxNotification;
import io.softa.starter.message.inbox.service.InboxNotificationService;

/**
 * REST controller for inbox notifications.
 */
@Tag(name = "InboxNotification")
@RestController
@RequestMapping("/InboxNotification")
public class InboxNotificationController
        extends EntityController<InboxNotificationService, InboxNotification, Long> {

    @Autowired
    private InboxNotificationService inboxNotificationService;

    @Operation(summary = "Mark a notification as read")
    @PostMapping("/markAsRead")
    public ApiResponse<Void> markAsRead(@RequestParam Long id) {
        inboxNotificationService.markAsRead(id);
        return ApiResponse.success();
    }

    @Operation(summary = "Mark all notifications as read for a recipient")
    @PostMapping("/markAllAsRead")
    public ApiResponse<Void> markAllAsRead(@RequestParam Long recipientId) {
        inboxNotificationService.markAllAsRead(recipientId);
        return ApiResponse.success();
    }

    @Operation(summary = "Count unread notifications for a recipient")
    @PostMapping("/countUnread")
    public ApiResponse<Integer> countUnread(@RequestParam Long recipientId) {
        return ApiResponse.success(inboxNotificationService.countUnread(recipientId));
    }

    // ── Self-service (always scoped to the authenticated caller) ──────────

    @Operation(summary = "Count unread notifications for the current user")
    @GetMapping("/myCountUnread")
    public ApiResponse<Integer> myCountUnread() {
        return ApiResponse.success(inboxNotificationService.countUnread(requireUserId()));
    }

    @Operation(summary = "List recent notifications for the current user")
    @GetMapping("/myRecent")
    public ApiResponse<List<InboxNotification>> myRecent(
            @RequestParam(defaultValue = "20") int limit) {
        int safeLimit = Math.clamp(limit, 1, 50);
        FlexQuery query = new FlexQuery(
                new Filters().eq(InboxNotification::getRecipientId, requireUserId()),
                Orders.ofDesc(InboxNotification::getCreatedTime));
        query.setLimitSize(safeLimit);
        return ApiResponse.success(inboxNotificationService.searchList(query));
    }

    @Operation(summary = "Mark one of the current user's notifications as read")
    @PostMapping("/myMarkAsRead")
    public ApiResponse<Void> myMarkAsRead(@RequestParam Long id) {
        InboxNotification notification = inboxNotificationService.getById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notification {0} not found.", id));
        if (!requireUserId().equals(notification.getRecipientId())) {
            throw new PermissionException("Cannot mark another user's notification as read.");
        }
        inboxNotificationService.markAsRead(id);
        return ApiResponse.success();
    }

    @Operation(summary = "Mark all of the current user's notifications as read")
    @PostMapping("/myMarkAllAsRead")
    public ApiResponse<Void> myMarkAllAsRead() {
        inboxNotificationService.markAllAsRead(requireUserId());
        return ApiResponse.success();
    }

    private static Long requireUserId() {
        Long userId = ContextHolder.getContext().getUserId();
        if (userId == null) {
            throw new PermissionException("Authentication required.");
        }
        return userId;
    }
}
