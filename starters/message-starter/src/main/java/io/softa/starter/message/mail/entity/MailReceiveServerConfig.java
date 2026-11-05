package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.MaskingType;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.mail.enums.ReceiveProtocol;

/**
 * Incoming mail server configuration (IMAP / IMAPS / POP3 / POP3S).
 * <p>
 * tenant_id = -1 — platform-tier, managed by the platform operator; invisible
 * to tenants, reached only by the dispatcher fallback.
 * tenant_id > 0  — tenant-level, tenant_id is auto-filled by the ORM.
 */
@Data
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true, activeControl = true, defaultOrder = {"sequence", "name"})
@Index(indexName = "idx_mail_recv_cfg_default", fields = {"tenantId", "isDefault"})
@EqualsAndHashCode(callSuper = true)
public class MailReceiveServerConfig extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID",
            description = "-1 = platform-tier (invisible to tenants); >0 = tenant-level. "
                    + "Auto-stamped by the ORM on writes when multi-tenancy is enabled.")
    private Long tenantId;

    @Field(label = "Config Name", required = true, length = 100)
    private String name;

    @Field(length = 500)
    private String description;

    @Field(label = "Receive Protocol", required = true,
            description = "IMAP/IMAPS: non-destructive observation (recommended) — fetched mails "
                    + "stay on the server, incremental fetch tracks IMAP UID per folder. "
                    + "POP3/POP3S: destructive drain — each fetched message is deleted from the server.")
    private ReceiveProtocol protocol;

    @Field(label = "Mail Server Host", required = true, length = 255)
    private String host;

    @Field(label = "Mail Server Port", required = true)
    private Integer port;

    @Field(label = "Enable SSL/TLS")
    private Boolean sslEnabled;

    @Field(label = "Auth Username", length = 255)
    private String username;

    @Field(length = 512, encrypted = true, copyable = false, unsearchable = true,
            maskingType = MaskingType.ALL,
            description = "Mailbox auth password — AES-encrypted at rest, masked in API reads. "
                    + "Omit the field on update to keep the stored value; a payload containing "
                    + "the mask symbol is rejected.")
    private String password;

    @Field(length = 255,
            description = "Comma-separated list of folders to fetch from (default: INBOX). "
                    + "Supports INBOX, Junk, and any custom folder name.")
    private String fetchFolders;

    @Field(description = "Whether this is the default receiving config for this tenant. "
                    + "At most one per tenant scope: the standard write endpoints demote every "
                    + "other default when a config is saved with this flag set.")
    private Boolean isDefault;

    /**
     * Framework active control: reads are auto-filtered to {@code active = true},
     * so a disabled row is retired from every list and every resolution without
     * being deleted (rows referenced by send records stay intact). Filter on
     * {@code active} explicitly to see disabled rows.
     */
    @Field(renamedFrom = "isEnabled")
    private Boolean active;

    /**
     * If receive-side failover is ever introduced, rename to {@code priority};
     * 'sequence' honestly reflects the current no-failover reality.
     */
    @Field(description = "Polling order for cron-driven fetch and display order in admin UIs. "
                    + "Ascending — lower = polled first / shown first. NOT a failover priority: "
                    + "all enabled configs get polled each tick.")
    private Integer sequence;
}
