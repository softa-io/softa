package io.softa.starter.message.mail.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.mail.enums.MailReceiveStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * Incoming mail record fetched from IMAP/POP3.
 * Written automatically by MailReceiveService; not created manually via API.
 */
@Data
@Schema(name = "MailReceiveRecord")
@EqualsAndHashCode(callSuper = true)
public class MailReceiveRecord extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Mail server config used to fetch this email")
    private Long serverConfigId;

    @Schema(description = "Unique message identifier from the mail server (IMAP UID / POP3 UIDL)")
    private String messageId;

    @Schema(description = "Sender address")
    private String fromAddress;

    @Schema(description = "To addresses (JSON array)")
    private String toAddresses;

    @Schema(description = "CC addresses (JSON array)")
    private String ccAddresses;

    @Schema(description = "Email subject")
    private String subject;

    @Schema(description = "Content type: TEXT or HTML")
    private String contentType;

    @Schema(description = "Full body content")
    private String body;

    @Schema(description = "Whether the email has attachments")
    private Boolean hasAttachments;

    @Schema(description = "Attachment file names (JSON array)")
    private String attachmentNames;

    @Schema(description = "Read status")
    private MailReceiveStatus status;

    @Schema(description = "Original timestamp from the mail server")
    private LocalDateTime receivedAt;

    @Schema(description = "Timestamp when this system fetched the email")
    private LocalDateTime fetchedAt;

    @Schema(description = "Source folder name (e.g. INBOX)")
    private String folderName;

    // ---- Phase-2 additions: bounce/receipt classification ----

    @Schema(description = "Mail type classification: Normal / ReadReceipt / Bounce")
    private String mailType;

    @Schema(description = "Original sent Message-ID that this email refers to (for receipt/bounce linking)")
    private String originalMessageId;

    @Schema(description = "SMTP reply code from bounce, e.g. 550")
    private String smtpReplyCode;

    @Schema(description = "Enhanced status code from bounce (RFC 3463), e.g. 5.1.1")
    private String enhancedStatusCode;

    @Schema(description = "Full bounce diagnostic message")
    private String diagnosticMessage;

    @Schema(description = "Failed recipient addresses (JSON array)")
    private String failedRecipients;

    // ---- Phase-3 addition: EML archive ----

    @Schema(description = "EML original file ID (file-starter)")
    private Long emlFileId;
}
