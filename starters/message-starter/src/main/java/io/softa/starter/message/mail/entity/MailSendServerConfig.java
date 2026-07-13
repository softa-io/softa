package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.mail.enums.SendProtocol;

/**
 * Outgoing mail server configuration (SMTP / SMTPS).
 * <p>
 * tenant_id = 0  — platform-level, managed by the ops platform or init scripts.
 * tenant_id > 0  — tenant-level, tenant_id is auto-filled by the ORM.
 */
@Data
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true)
@Index(indexName = "idx_mail_send_cfg_default", fields = {"tenantId", "isDefault"})
@EqualsAndHashCode(callSuper = true)
public class MailSendServerConfig extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID",
            description = "0 = platform-level (shared across tenants); >0 = tenant-level. "
                    + "Auto-stamped by the ORM on writes when multi-tenancy is enabled.")
    private Long tenantId;

    @Field(label = "Config Name", required = true, length = 100)
    private String name;

    @Field(length = 500)
    private String description;

    @Field(label = "Send Protocol", required = true,
            description = "Protocol: SMTP or SMTPS")
    private SendProtocol protocol;

    @Field(label = "SMTP Server Host", required = true, length = 255)
    private String host;

    @Field(label = "SMTP Server Port", required = true)
    private Integer port;

    @Field(label = "SSL Enabled",
            description = "Use implicit TLS / SMTPS — the TLS handshake happens immediately after TCP, "
                    + "the entire session is encrypted from the first byte. Typical port: 465. "
                    + "Set this OR starttlsEnabled, not both. "
                    + "Choose this when the provider's docs say 'SSL/TLS' or list port 465.")
    private Boolean sslEnabled;

    @Field(label = "STARTTLS Enabled",
            description = "Use STARTTLS / explicit TLS — the connection starts as plaintext SMTP, "
                    + "then upgrades to TLS via the STARTTLS command. Typical ports: 587 (submission) or 25 (legacy). "
                    + "Set this OR sslEnabled, not both. "
                    + "Choose this when the provider's docs say 'STARTTLS' or list port 587.")
    private Boolean starttlsEnabled;

    @Field(label = "Auth Username", length = 255)
    private String username;

    @Field(length = 500)
    private String password;

    @Field(length = 255,
            description = "From address displayed in outgoing emails")
    private String fromAddress;

    @Field(length = 100,
            description = "From display name")
    private String fromName;

    @Field(label = "Reply-To Address", length = 255,
            description = "Default Reply-To address for emails sent through this server config. "
                    + "Resolution chain at send time (highest to lowest priority): "
                    + "SendMailDTO.replyTo > MailTemplate.replyTo > MailSendServerConfig.replyToAddress.")
    private String replyToAddress;

    @Field(description = "Maximum emails sent per day (null = unlimited)")
    private Integer dailySendLimit;

    @Field(description = "Rate limit: max emails per minute")
    private Integer rateLimitPerMinute;

    @Field(description = "Whether this is the default sending config for this tenant")
    private Boolean isDefault;

    @Field(description = "Whether this config is enabled")
    private Boolean isEnabled;

    @Field(description = "Tie-break order among multiple isDefault=true configs + display "
                    + "order in admin UIs. Ascending — lower wins. NOT a failover priority: dispatcher "
                    + "picks the first matching default and stops; other defaults are never tried as "
                    + "backup. If send-side failover is ever introduced, rename to 'priority' then.")
    private Integer sequence;

    @Field(description = "Whether to request read receipts by default (Disposition-Notification-To header)")
    private Boolean readReceiptEnabled;

}
