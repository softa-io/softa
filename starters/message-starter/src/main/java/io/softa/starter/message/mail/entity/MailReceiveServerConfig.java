package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.mail.enums.ReceiveProtocol;

/**
 * Incoming mail server configuration (IMAP / IMAPS / POP3 / POP3S).
 * <p>
 * tenant_id = 0  — platform-level, managed by the ops platform or init scripts.
 * tenant_id > 0  — tenant-level, tenant_id is auto-filled by the ORM.
 */
@Data
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true)
@Index(indexName = "idx_mail_recv_cfg_default", fields = {"tenantId", "isDefault"})
@EqualsAndHashCode(callSuper = true)
public class MailReceiveServerConfig extends AuditableModel {

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

    @Field(label = "Receive Protocol", required = true,
            description = "Receive protocol. "
                    + "IMAP/IMAPS: non-destructive observation (recommended) — fetched mails stay on server, "
                    + "incremental fetch tracks IMAP UID per folder. "
                    + "POP3/POP3S: destructive drain — each fetched message is deleted from the server. "
                    + "See ReceiveProtocol enum for per-value detail.")
    private ReceiveProtocol protocol;

    @Field(label = "Mail Server Host", required = true, length = 255)
    private String host;

    @Field(label = "Mail Server Port", required = true)
    private Integer port;

    @Field(label = "Enable SSL/TLS")
    private Boolean sslEnabled;

    @Field(label = "Auth Username", length = 255)
    private String username;

    @Field(length = 255)
    private String password;

    @Field(length = 255,
            description = "Comma-separated list of folders to fetch from (default: INBOX). "
                    + "Supports INBOX, Junk, and any custom folder name.")
    private String fetchFolders;

    @Field(description = "Whether this is the default receiving config for this tenant")
    private Boolean isDefault;

    @Field(description = "Whether this config is enabled")
    private Boolean isEnabled;

    @Field(description = "Polling order for cron-driven fetch + display order in admin UIs. "
                    + "Ascending — lower = polled first / shown first. NOT a failover priority: "
                    + "all enabled configs get polled each tick. If receive-side failover semantics "
                    + "are ever introduced, rename to 'priority' then; current name 'sequence' "
                    + "honestly reflects the no-failover reality.")
    private Integer sequence;
}
