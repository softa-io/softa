package io.softa.starter.message.mail.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.mail.enums.BodyMode;
import io.softa.starter.message.mail.enums.MailPriority;
import io.softa.starter.message.mail.enums.MailSendStatus;

/**
 * Outgoing mail record.
 * Written automatically by MessageService; not created manually via API.
 */
@Data
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, versionLock = true, copyable = false, multiTenant = true)
@Index(indexName = "idx_mail_send_tenant_status", fields = {"tenantId", "status"})
@Index(indexName = "idx_mail_send_sent_at", fields = {"sentAt"})
@Index(indexName = "idx_mail_send_status_updated", fields = {"status", "updatedTime"})
@Index(indexName = "idx_mail_send_status_retry", fields = {"status", "nextRetryAt"})
@Index(indexName = "idx_message_id", fields = {"messageId"})
@EqualsAndHashCode(callSuper = true)
public class MailSendRecord extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID",
            description = "0 = platform-level (shared across tenants); >0 = tenant-level. "
                    + "Auto-stamped by the ORM on writes when multi-tenancy is enabled.")
    private Long tenantId;

    @Field(label = "Server Config ID", description = "Mail server config used to send this email")
    private Long serverConfigId;

    @Field(length = 255)
    private String fromAddress;

    @Field
    private List<String> toAddresses;

    @Field(label = "CC Addresses")
    private List<String> ccAddresses;

    @Field(label = "BCC Addresses")
    private List<String> bccAddresses;

    @Field(length = 500)
    private String subject;

    @Field(required = true, description = "Body shape used to send. "
            + "HTML — bodyHtml only. "
            + "PLAIN — bodyText only. "
            + "HTML_WITH_DERIVED_PLAIN — bodyHtml + bodyText (plain machine-extracted at send time). "
            + "HTML_WITH_AUTHORED_PLAIN — bodyHtml + bodyText (plain hand-authored). "
            + "Audit can distinguish DERIVED vs AUTHORED to find sends whose plain text was reviewed by a human.")
    private BodyMode bodyMode;

    @Field(label = "Body HTML", description = "HTML body persisted verbatim for retry fidelity. Null for PLAIN mode.")
    private String bodyHtml;

    @Field(description = "Plain-text body persisted verbatim for retry fidelity. "
            + "Null for HTML mode; populated for PLAIN / HTML_WITH_DERIVED_PLAIN / HTML_WITH_AUTHORED_PLAIN.")
    private String bodyText;

    @Field(fieldType = FieldType.MULTI_FILE, description = "Attachment FileInfo list. Persisted as fileIds (List<Long> CSV) by ORM; "
            + "resolved back to FileInfo on read so consumers can stream attachment bytes from "
            + "file-starter at SMTP send time. Null/empty when the email has no attachments.")
    private List<FileInfo> attachments;

    @Field(required = true, description = "Send status")
    private MailSendStatus status;

    @Field(description = "Number of send attempts")
    private Integer retryCount;

    @Field(required = true, description = "Optimistic-lock version. Bumped on every state transition.")
    private Long version;

    @Field(description = "Earliest time at which the next retry should be attempted")
    private LocalDateTime nextRetryAt;

    @Field(description = "Provider-specific error code on failure", length = 100)
    private String errorCode;

    @Field(description = "Error message on failure")
    private String errorMessage;

    @Field(description = "Timestamp when the email was accepted by the SMTP server")
    private LocalDateTime sentAt;

    @Field(label = "Message ID", description = "SMTP Message-ID header value", length = 255)
    private String messageId;

    // ---- Phase-1 additions: send enhancements ----

    @Field(description = "Whether a read receipt was requested for this email")
    private Boolean readReceiptRequested;

    @Field(description = "Priority level used when sending: HIGH / NORMAL / LOW")
    private MailPriority priority;

    @Field(description = "Reply-To address actually used when sending — final value after the "
            + "dto > template > config fallback chain. Persisted so retry replays the same Reply-To.", length = 255)
    private String replyTo;

    // ---- Phase-2 additions: receive-side bounce/receipt tracking ----

    @Field(description = "Whether a read receipt has been received for this email")
    private Boolean readReceiptReceived;

    @Field(description = "Timestamp when the read receipt was received")
    private LocalDateTime readReceiptReceivedAt;

    @Field(description = "Whether this email bounced (rejection / NDR received)")
    private Boolean bounced;

    @Field(description = "Bounce code summary, e.g. '550 5.1.1'", length = 20)
    private String bounceCode;
}
