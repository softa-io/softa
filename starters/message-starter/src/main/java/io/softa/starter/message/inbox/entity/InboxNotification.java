package io.softa.starter.message.inbox.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.inbox.enums.NotificationType;

/**
 * In-app notification delivered to a specific user.
 * <p>
 * Notifications are read-only informational messages pushed by the system,
 * workflow engine, or manually by other users. They support optional expiry
 * and can carry a reference to the originating object via {@code sourceModel}
 * and {@code sourceId}.
 * <p>
 * tenant_id = 0 — platform-level notification; tenant_id > 0 — tenant-scoped.
 */
@Data
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, copyable = false, multiTenant = true)
@Index(indexName = "idx_recipient_read", fields = {"recipientId", "isRead"})
@Index(indexName = "idx_inbox_notif_tenant", fields = {"tenantId"})
@EqualsAndHashCode(callSuper = true)
public class InboxNotification extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID",
            description = "0 = platform-level (shared across tenants); >0 = tenant-level. "
                    + "Auto-stamped by the ORM on writes when multi-tenancy is enabled.")
    private Long tenantId;

    @Field(label = "Recipient User ID", required = true)
    private Long recipientId;

    @Field(required = true, length = 200)
    private String title;

    @Field(description = "Notification body content")
    private String content;

    @Field(description = "Source category: System, Workflow, or Manual")
    private NotificationType notificationType;

    @Field(description = "Source metadata model name (matches SysModel.modelName), e.g. FlowInstance (nullable)", length = 50)
    private String sourceModel;

    @Field(label = "Source ID", description = "Source object ID (nullable)")
    private Long sourceId;

    @Field(description = "Whether the recipient has read this notification")
    private Boolean isRead;

    @Field(description = "Timestamp when the notification was read")
    private LocalDateTime readAt;

    @Field(description = "Optional expiry time after which the notification is no longer shown")
    private LocalDateTime expiredAt;
}
