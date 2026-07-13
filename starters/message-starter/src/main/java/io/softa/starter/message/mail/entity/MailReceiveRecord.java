package io.softa.starter.message.mail.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.message.mail.enums.BodyMode;
import io.softa.starter.message.mail.enums.MailReceiveStatus;
import io.softa.starter.message.mail.enums.ReceivedMailType;

/**
 * Incoming mail record fetched from IMAP/POP3.
 * Written automatically by MailReceiveService; not created manually via API.
 */
@Data
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        businessKey = {"serverConfigId", "messageId"},
        copyable = false,
        multiTenant = true
)
@Index(indexName = "uk_server_msg", fields = {"serverConfigId", "messageId"}, unique = true)
@Index(indexName = "idx_mail_recv_tenant_status", fields = {"tenantId", "status"})
@Index(indexName = "idx_truncation", fields = {"truncationReason"})
@EqualsAndHashCode(callSuper = true)
public class MailReceiveRecord extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID",
            description = "0 = platform-level (shared across tenants); >0 = tenant-level. "
                    + "Auto-stamped by the ORM on writes when multi-tenancy is enabled.")
    private Long tenantId;

    @Field(description = "Mail server config used to fetch this email")
    private Long serverConfigId;

    @Field(length = 255,
            description = "RFC 5322 Message-ID header value (preserved across SMTP/IMAP/POP3); "
                    + "falls back to a 'synthetic:' SHA-256 key when the email lacks a Message-ID header. "
                    + "Used together with serverConfigId as the dedup key on re-fetch.")
    private String messageId;

    @Field(length = 255, description = "Sender address")
    private String fromAddress;

    @Field(description = "To addresses")
    private List<String> toAddresses;

    @Field(description = "CC addresses")
    private List<String> ccAddresses;

    @Field(length = 500, description = "Email subject")
    private String subject;

    @Field(length = 16777215,
            description = "Plain-text body for list preview, search, and any consumer that "
                    + "wants a single guaranteed string. When the original email had a real text/plain "
                    + "part it is stored verbatim; when the email is HTML-only it is derived from "
                    + "bodyHtml at write time via HtmlUtils.toText (one-shot CPU on ingest, not on "
                    + "every read). Use bodyMode to tell the two apart for audit/forensics.")
    private String bodyText;

    @Field(length = 16777215,
            description = "Sender's HTML body, taken verbatim from the text/html MIME part. "
                    + "Null for plain-text-only emails.")
    private String bodyHtml;

    @Field(description = "Original wire MIME shape captured during parsing, before any "
                    + "derivation. HTML — single text/html part (bodyText is derived from bodyHtml). "
                    + "PLAIN — single text/plain part. HTML_WITH_PLAIN_ALT — multipart/alternative "
                    + "carrying both (bodyText is the sender's hand-written version). "
                    + "Null when the email has no text body at all (pure attachments / calendar invites). "
                    + "Front-end uses this to pick the renderer; audit code uses it to tell whether "
                    + "bodyText is verbatim or derived.")
    private BodyMode bodyMode;

    @Field(fieldType = FieldType.MULTI_FILE,
            description = "Per-attachment metadata. Each item corresponds to a non-inline MIME part "
                    + "extracted from the email and uploaded to object storage via file-starter. "
                    + "Persisted as fileIds (List<Long> CSV) by ORM; resolved to FileInfo on read. "
                    + "Null/empty when the email has no attachments or fileService is unavailable.")
    private List<FileInfo> attachments;

    @Field(required = true, description = "Read status")
    private MailReceiveStatus status;

    @Field(description = "Original timestamp from the mail server")
    private LocalDateTime receivedAt;

    @Field(description = "Timestamp when this system fetched the email")
    private LocalDateTime fetchedAt;

    @Field(length = 100, description = "Source folder name (e.g. INBOX)")
    private String folderName;

    // ---- Phase-2 additions: bounce/receipt classification ----

    @Field(description = "Primary mutually-exclusive content type: "
                    + "Normal / ReadReceipt / Bounce / AutoReply / CalendarInvite / Unknown. "
                    + "Only ReadReceipt and Bounce trigger downstream actions; the others are "
                    + "recorded for observability. Orthogonal properties (mailing-list distribution, "
                    + "encryption, spam) live as separate boolean flags below.")
    private ReceivedMailType mailType;

    @Field(description = "True when the message bears List-Id / List-Unsubscribe / "
                    + "Precedence:bulk markers indicating distribution via a mailing list. "
                    + "Orthogonal to mailType — a mailing-list bounce or calendar invite has both signals.")
    private Boolean isMailingList;

    @Field(description = "True when the body is wrapped in PGP-MIME (multipart/encrypted) or "
                    + "S/MIME (application/pkcs7-mime, smime-type=enveloped-data). Tells the inbox UI "
                    + "the body is opaque without a key, regardless of mailType.")
    private Boolean isEncrypted;

    @Field(description = "True when standard anti-spam markers are present "
                    + "(X-Spam-Flag: YES, X-Spam-Status: Yes, Exchange SCL ≥ 5). Reputation overlay; "
                    + "orthogonal to mailType — backscatter spam has mailType=Bounce + isSpam=true.")
    private Boolean isSpam;

    @Field(length = 255,
            description = "Original sent Message-ID that this email refers to (for receipt/bounce linking)")
    private String originalMessageId;

    @Field(length = 10, description = "SMTP reply code from bounce, e.g. 550")
    private String smtpReplyCode;

    @Field(length = 20,
            description = "Enhanced status code from bounce (RFC 3463), e.g. 5.1.1")
    private String enhancedStatusCode;

    @Field(description = "Full bounce diagnostic message")
    private String diagnosticMessage;

    @Field(description = "Failed recipient addresses extracted from the DSN report.")
    private List<String> failedRecipients;

    // ---- Phase-3 addition: EML archive ----

    @Field(description = "EML original file ID (file-starter)")
    private Long emlFileId;

    // ---- Phase-4 addition: resource-limit truncation ----

    @Field(length = 32,
            description = "Why this email was processed in a degraded way. "
                    + "Null when fully processed. Values: "
                    + "BodyTooLarge (size > maxMessageSize, only envelope persisted) / "
                    + "AttachmentTooLarge (one or more parts skipped, body intact) / "
                    + "MimeDepthExceeded (nested structure aborted parsing) / "
                    + "MimePartsExceeded (too many parts, parsing aborted) / "
                    + "ParseFailed (unexpected JavaMail error). "
                    + "Orthogonal to mailType — a bounce can also be truncated.")
    private String truncationReason;
}
