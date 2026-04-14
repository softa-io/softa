package io.softa.starter.message.mail.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.mail.enums.AuthType;
import io.softa.starter.message.mail.enums.SendProtocol;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * Outgoing mail server configuration (SMTP / SMTPS).
 * <p>
 * tenant_id = 0  — platform-level, managed by the ops platform or init scripts.
 * tenant_id > 0  — tenant-level, tenant_id is auto-filled by the ORM.
 */
@Data
@Schema(name = "MailSendServerConfig")
@EqualsAndHashCode(callSuper = true)
public class MailSendServerConfig extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Config name")
    private String name;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Protocol: SMTP or SMTPS")
    private SendProtocol protocol;

    @Schema(description = "SMTP server host")
    private String host;

    @Schema(description = "SMTP server port")
    private Integer port;

    @Schema(description = "Enable SSL/TLS")
    private Boolean sslEnabled;

    @Schema(description = "Enable STARTTLS")
    private Boolean starttlsEnabled;

    @Schema(description = "Connection timeout in milliseconds")
    private Integer connectionTimeoutMs;

    @Schema(description = "Read timeout in milliseconds")
    private Integer readTimeoutMs;

    @Schema(description = "Maximum number of connections in the pool")
    private Integer maxConnections;

    @Schema(description = "Authentication type: Password or OAuth2")
    private AuthType authType;

    @Schema(description = "Auth username")
    private String username;

    @Schema(description = "Password")
    private String password;

    @Schema(description = "From address displayed in outgoing emails")
    private String fromAddress;

    @Schema(description = "From display name")
    private String fromName;

    @Schema(description = "Reply-to address")
    private String replyToAddress;

    @Schema(description = "Maximum emails sent per day (null = unlimited)")
    private Integer dailySendLimit;

    @Schema(description = "Rate limit: max emails per minute")
    private Integer rateLimitPerMinute;

    @Schema(description = "Whether this is the default sending config for this tenant")
    private Boolean isDefault;

    @Schema(description = "Whether this config is enabled")
    private Boolean isEnabled;

    @Schema(description = "Sort order for multiple configs")
    private Integer sortOrder;

    // ---- Phase-1 additions: send enhancements ----

    @Schema(description = "Whether to request read receipts by default (Disposition-Notification-To header)")
    private Boolean readReceiptEnabled;

    @Schema(description = "Maximum retry attempts on send failure (null or 0 = no retry)")
    private Integer maxRetryCount;

    @Schema(description = "Delay in seconds between retry attempts (default 60)")
    private Integer retryIntervalSeconds;

    @Schema(description = "Enable Jakarta Mail debug output (full SMTP protocol logging)")
    private Boolean debugEnabled;
}
