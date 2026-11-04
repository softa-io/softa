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
import io.softa.framework.orm.annotation.SkipPermissionCheck;
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
    //
    // Each of these carries @SkipPermissionCheck, and the annotation sits HERE rather than on the
    // service methods below on purpose: this is the layer that takes the caller's id from the
    // request context, so the bound the waiver relies on is visible in the same method. The service
    // methods underneath take recipientId as a parameter and stay checked — waiving them would let
    // whatever passes an id next read someone else's inbox.
    //
    // Why the waiver is needed at all: InboxNotification is anchorless — nothing a business role is
    // granted references it — so PermissionServiceImpl.scopeWithoutGrant finds no referencer and
    // fails closed to matchNone(). The filters below already pin every row to the caller, so row
    // scope cannot narrow anything; it can only empty the result. And it does so silently: the bell
    // reports zero unread and an empty list rather than an error, which is why this went unnoticed
    // while the same defect on UserProfile surfaced as a 400.
    //
    // That these endpoints are open to any authenticated caller is already declared in the
    // application's permission.authenticated-bypass-patterns (/InboxNotification/my*). That
    // configuration only opens the endpoint gate; this answers the same question at the data layer.

    @Operation(summary = "Count unread notifications for the current user")
    @GetMapping("/myCountUnread")
    @SkipPermissionCheck
    public ApiResponse<Integer> myCountUnread() {
        return ApiResponse.success(inboxNotificationService.countUnread(requireUserId()));
    }

    @Operation(summary = "List recent notifications for the current user")
    @GetMapping("/myRecent")
    @SkipPermissionCheck
    public ApiResponse<List<InboxNotification>> myRecent(
            @RequestParam(defaultValue = "20") int limit) {
        int safeLimit = Math.clamp(limit, 1, 50);
        FlexQuery query = new FlexQuery(
                new Filters().eq(InboxNotification::getRecipientId, requireUserId()),
                Orders.ofDesc(InboxNotification::getId));
        query.setLimitSize(safeLimit);
        return ApiResponse.success(inboxNotificationService.searchList(query));
    }

    /**
     * The one that reads by id rather than by the caller. {@code getById} below is reachable for any
     * notification once row scope is waived, so the ownership test that follows is not a nicety —
     * it is what replaces the check, and it must stay ahead of the mutation.
     */
    @SkipPermissionCheck
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

    /**
     * The caller opened what their notifications are about — an approval, say — so those still unread are news no
     * more. Bounded by the caller like the rest of {@code my*}: another user's notifications are never touched.
     */
    @SkipPermissionCheck
    @Operation(summary = "Mark the current user's notifications about one object as read",
            description = "Marks read every unread notification of the current user whose source is the object named "
                    + "— a flow instance, when its approval is opened. Returns how many were marked.")
    @PostMapping("/myMarkAsReadBySource")
    public ApiResponse<Integer> myMarkAsReadBySource(@RequestParam String sourceModel, @RequestParam String sourceId) {
        return ApiResponse.success(inboxNotificationService.markReadBySource(requireUserId(), sourceModel, sourceId));
    }

    @Operation(summary = "Mark all of the current user's notifications as read")
    @PostMapping("/myMarkAllAsRead")
    @SkipPermissionCheck
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
