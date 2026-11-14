package io.softa.starter.message.mail.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.message.mail.enums.BodyMode;
import io.softa.starter.message.mail.enums.MailPriority;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.enums.MailServerOwner;

/**
 * Outgoing mail record.
 * Written automatically by MessageService; not created manually via API.
 *
 * <p>Acceptance stores the request as the caller made it — recipients, any explicit content, the
 * template it named and its variables, the tier. The first delivery attempt renders the template,
 * chooses the server and charges the monthly quota, and writes the result back here; retries replay
 * what that attempt prepared. A request that cannot be prepared (no server, no template, quota used
 * up) still leaves its record, FAILED with the reason.
 *
 * <p>Searched by recipient and subject: the send log is where "this user never got the email" is
 * looked into, and the recipient is what the person investigating has in hand.
 *
 * <p>Not multi-tenant: the table is the mail delivery queue the platform works through for every
 * tenant, and one record can involve both sides — a tenant's mail relayed through a platform server
 * has the tenant's content and the platform's server, quota and bounces. {@code tenantId} records
 * only whom the mail is filed under: the tenant it is made for, whatever the scope (a platform notice
 * about one of its accounts included), {@code -1} when no tenant can be named. Acceptance writes it
 * and the ORM neither stamps nor filters it.
 * The platform's pages read the table itself; a tenant reads its own rows through the
 * {@link TenantMailSendRecord} projection, which leaves out the bodies and template variables.
 *
 * <p>Keeps no change log: the delivery loop writes every record several times — the claim, the
 * preparation, the outcome — and its own fields are its history. A log would also copy the rendered
 * bodies, with their sign-in links and codes, into the change-log store.
 */
@Data
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, versionLock = true, copyable = false, enableAuditLog = false,
        searchName = {"toAddresses", "subject"})
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
            description = "Whom the mail is filed under: the tenant it is made for, whatever the scope (a "
                    + "platform notice about one of its accounts included); -1 when no tenant can be named. "
                    + "Decides who sees it in the tenant send log and whose monthly quota a send through a "
                    + "platform server draws on.")
    private Long tenantId;

    @Field(description = "Scope the caller declared: true = PLATFORM (a platform template, a platform "
            + "server), false = TENANT (the tenant's template, its own server or the platform's relay). "
            + "Decides which tier renders and carries the mail, not whom it is filed under.")
    private Boolean platformScope;

    @Field(label = "Template ID", description = "Template row the caller addressed (editor test sends); "
            + "takes precedence over templateCode")
    private Long templateId;

    @Field(length = 100, description = "Template code the caller asked for")
    private String templateCode;

    @Field(fieldType = FieldType.JSON, description = "Template placeholder values the caller passed")
    private JsonNode templateVariables;

    @Field(label = "Trace ID", description = "Trace id of the request that submitted the send; "
            + "every delivery attempt runs and logs under it")
    private String traceId;

    @Field(label = "Server Config ID", description = "Send server that carries this email: the caller's or "
            + "template's choice, else the default the first delivery attempt resolved. An id in the table of "
            + "serverOwner.")
    private Long serverConfigId;

    @Field(description = "Whose send server carries this email, fixed by the first delivery attempt: the "
            + "tenant's own, or the platform's (the platform's mail, or a tenant's relayed). Retries replay "
            + "the same owner's server; bounces and receipts are matched by the receive servers of the "
            + "same owner. Empty until the record is prepared.")
    private MailServerOwner serverOwner;

    @Field(length = 255, description = "Sender address, fixed by the first delivery attempt from the "
            + "server config; empty until then")
    private String fromAddress;

    @Field
    private List<String> toAddresses;

    @Field(label = "CC Addresses")
    private List<String> ccAddresses;

    @Field(label = "BCC Addresses")
    private List<String> bccAddresses;

    @Field(length = 500)
    private String subject;

    @Field(description = "Body shape used to send. "
            + "HTML — bodyHtml only. "
            + "PLAIN — bodyText only. "
            + "HTML_WITH_DERIVED_PLAIN — bodyHtml + bodyText (plain derived at send time). "
            + "HTML_WITH_AUTHORED_PLAIN — bodyHtml + bodyText (plain hand-authored). "
            + "DERIVED vs AUTHORED tells audit whether the plain text was human-reviewed. "
            + "Empty until the first delivery attempt renders a template.")
    private BodyMode bodyMode;

    @Field(label = "Body HTML", fieldType = FieldType.TEXT,
            description = "HTML body persisted verbatim for retry fidelity. Null for PLAIN mode.")
    private String bodyHtml;

    @Field(fieldType = FieldType.TEXT,
            description = "Plain-text body persisted verbatim for retry fidelity. "
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

    @Field(length = 1000, description = "Error message on failure")
    private String errorMessage;

    @Field(description = "Timestamp when the email was accepted by the SMTP server")
    private LocalDateTime sentAt;

    @Field(description = "When the latest delivery attempt started: the delivery loop claimed the record "
            + "(status → Sending).")
    private LocalDateTime attemptStartedAt;

    @Field(label = "Send Duration (ms)", description = "How long the SMTP call of the successful attempt took, "
            + "in milliseconds — connection setup (when no pooled connection was reused) and attachment "
            + "streaming included. Null until the record is sent.")
    private Integer sendDurationMs;

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
