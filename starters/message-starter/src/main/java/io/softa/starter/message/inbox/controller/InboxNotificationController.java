package io.softa.starter.message.inbox.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.inbox.entity.InboxNotification;
import io.softa.starter.message.inbox.service.InboxNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
