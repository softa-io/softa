package io.softa.starter.message.mail.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.enums.MailServerOwner;

/**
 * A tenant's view of its own send log: a read-only projection over {@link MailSendRecord}'s table,
 * multi-tenant, so a tenant reads exactly the rows filed under it — the mail it sent, through its own
 * servers or relayed through the platform's, and the platform's notices made for it (its accounts'
 * security notices, its people's sign-in codes, its subscription reminders), which its quota paid for.
 * Mail no tenant can be named for is filed under {@code -1} and is not among them.
 * <p>
 * It carries what a tenant needs to answer "did this mail go out": recipients, subject, template code,
 * status and timing, the error, whether it bounced or was read, and whose server carried it. Bodies and
 * template variables are left out: they hold the sign-in links, codes and documents of the mail they
 * render. Column declarations repeat {@link MailSendRecord}'s verbatim, which owns the table.
 */
@Data
@Model(label = "Tenant Mail Send Record", tableName = "mail_send_record", projection = true,
        idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true, copyable = false,
        searchName = {"toAddresses", "subject"})
@EqualsAndHashCode(callSuper = true)
public class TenantMailSendRecord extends AuditableModel {

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

    @Field(length = 100, description = "Template code the caller asked for")
    private String templateCode;

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

    @Field(required = true, description = "Send status")
    private MailSendStatus status;

    @Field(description = "Number of send attempts")
    private Integer retryCount;

    @Field(description = "Earliest time at which the next retry should be attempted")
    private LocalDateTime nextRetryAt;

    @Field(description = "Provider-specific error code on failure", length = 100)
    private String errorCode;

    @Field(length = 1000, description = "Error message on failure")
    private String errorMessage;

    @Field(description = "Timestamp when the email was accepted by the SMTP server")
    private LocalDateTime sentAt;

    @Field(description = "Whether a read receipt was requested for this email")
    private Boolean readReceiptRequested;

    @Field(description = "Whether a read receipt has been received for this email")
    private Boolean readReceiptReceived;

    @Field(description = "Timestamp when the read receipt was received")
    private LocalDateTime readReceiptReceivedAt;

    @Field(description = "Whether this email bounced (rejection / NDR received)")
    private Boolean bounced;

    @Field(description = "Bounce code summary, e.g. '550 5.1.1'", length = 20)
    private String bounceCode;

    @Field(description = "Whose send server carries this email, fixed by the first delivery attempt: the "
            + "tenant's own, or the platform's (the platform's mail, or a tenant's relayed). Retries replay "
            + "the same owner's server; bounces and receipts are matched by the receive servers of the "
            + "same owner. Empty until the record is prepared.")
    private MailServerOwner serverOwner;
}
