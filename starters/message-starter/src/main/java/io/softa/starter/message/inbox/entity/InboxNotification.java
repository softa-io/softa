package io.softa.starter.message.inbox.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.inbox.enums.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * In-app notification delivered to a specific user.
 * <p>
 * Notifications are read-only informational messages pushed by the system,
 * workflow engine, or manually by other users. They support optional expiry
 * and can carry a reference to the originating object via {@code sourceType}
 * and {@code sourceId}.
 * <p>
 * tenant_id = 0 — platform-level notification; tenant_id > 0 — tenant-scoped.
 */
@Data
@Schema(name = "InboxNotification")
@EqualsAndHashCode(callSuper = true)
public class InboxNotification extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Recipient user ID")
    private Long recipientId;

    @Schema(description = "Notification title")
    private String title;

    @Schema(description = "Notification body content")
    private String content;

    @Schema(description = "Source category: System, Workflow, or Manual")
    private NotificationType notificationType;

    @Schema(description = "Source object type, e.g. FLOW_INSTANCE, ORDER (nullable)")
    private String sourceType;

    @Schema(description = "Source object ID (nullable)")
    private Long sourceId;

    @Schema(description = "Whether the recipient has read this notification")
    private Boolean isRead;

    @Schema(description = "Timestamp when the notification was read")
    private LocalDateTime readAt;

    @Schema(description = "Optional expiry time after which the notification is no longer shown")
    private LocalDateTime expiredAt;
}
