package io.softa.starter.message.mail.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.mail.enums.AuthType;
import io.softa.starter.message.mail.enums.ReceiveProtocol;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * Incoming mail server configuration (IMAP / IMAPS / POP3 / POP3S).
 * <p>
 * tenant_id = 0  — platform-level, managed by the ops platform or init scripts.
 * tenant_id > 0  — tenant-level, tenant_id is auto-filled by the ORM.
 */
@Data
@Schema(name = "MailReceiveServerConfig")
@EqualsAndHashCode(callSuper = true)
public class MailReceiveServerConfig extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Config name")
    private String name;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Protocol: IMAP, IMAPS, POP3, POP3S")
    private ReceiveProtocol protocol;

    @Schema(description = "Mail server host")
    private String host;

    @Schema(description = "Mail server port")
    private Integer port;

    @Schema(description = "Enable SSL/TLS")
    private Boolean sslEnabled;

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

    @Schema(description = "IMAP folder to poll (default: INBOX)")
    private String inboxFolder;

    @Schema(description = "Delete message from server after fetching (POP3 only)")
    private Boolean deleteAfterFetch;

    @Schema(description = "Whether this is the default receiving config for this tenant")
    private Boolean isDefault;

    @Schema(description = "Whether this config is enabled")
    private Boolean isEnabled;

    @Schema(description = "Sort order for multiple configs")
    private Integer sortOrder;

    // ---- Phase-2 addition: multi-folder ----

    @Schema(description = "Comma-separated list of folders to fetch from (default: INBOX). "
            + "Supports INBOX, Junk, and any custom folder name.")
    private String fetchFolders;

    // ---- Phase-3 additions: scheduled fetch ----

    @Schema(description = "Whether to enable scheduled mail fetching")
    private Boolean scheduledFetchEnabled;

    @Schema(description = "Cron expression for scheduled fetch, e.g. '0 */5 * * * ?'")
    private String fetchCronExpression;
}
