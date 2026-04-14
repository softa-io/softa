package io.softa.starter.message.mail.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * Outgoing mail record.
 * Written automatically by MailSendService; not created manually via API.
 */
@Data
@Schema(name = "MailSendRecord")
@EqualsAndHashCode(callSuper = true)
public class MailSendRecord extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Mail server config used to send this email")
    private Long serverConfigId;

    @Schema(description = "From address")
    private String fromAddress;

    @Schema(description = "To addresses (JSON array)")
    private String toAddresses;

    @Schema(description = "CC addresses (JSON array)")
    private String ccAddresses;

    @Schema(description = "BCC addresses (JSON array)")
    private String bccAddresses;

    @Schema(description = "Email subject")
    private String subject;

    @Schema(description = "Content type: TEXT or HTML")
    private String contentType;

    @Schema(description = "First 500 characters of the body for list display")
    private String bodyPreview;

    @Schema(description = "Full email body for retry fidelity")
    private String body;

    @Schema(description = "Whether the email has attachments")
    private Boolean hasAttachments;

    @Schema(description = "Send status")
    private MailSendStatus status;

    @Schema(description = "Number of send attempts")
    private Integer retryCount;

    @Schema(description = "Error message on failure")
    private String errorMessage;

    @Schema(description = "Timestamp when the email was accepted by the SMTP server")
    private LocalDateTime sentAt;

    @Schema(description = "SMTP Message-ID header value")
    private String messageId;

    // ---- Phase-1 additions: send enhancements ----

    @Schema(description = "Whether a read receipt was requested for this email")
    private Boolean readReceiptRequested;

    @Schema(description = "Priority level used when sending: High / Normal / Low")
    private String priority;

    // ---- Phase-2 additions: receive-side bounce/receipt tracking ----

    @Schema(description = "Whether a read receipt has been received for this email")
    private Boolean readReceiptReceived;

    @Schema(description = "Timestamp when the read receipt was received")
    private LocalDateTime readReceiptReceivedAt;

    @Schema(description = "Whether this email bounced (rejection / NDR received)")
    private Boolean bounced;

    @Schema(description = "Bounce code summary, e.g. '550 5.1.1'")
    private String bounceCode;
}
