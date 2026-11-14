package io.softa.starter.message.mail.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.MaskingType;
import io.softa.starter.message.mail.enums.ReceiveProtocol;

/**
 * Fields of an incoming mail server (IMAP / IMAPS / POP3 / POP3S), shared by
 * the two owners' tables: {@link MailReceiveServerConfig} (a tenant's own
 * mailboxes) and {@link PlatformMailReceiveServer} (the platform's). Mail
 * belongs to the owner of the server it arrived through, and receiving never
 * falls back from one owner to the other.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class AbstractMailReceiveServer extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

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

    @Field(description = "Whether this is its owner's default receive server — the tenant's, or the "
                    + "platform's. At most one per owner: the standard write endpoints demote every "
                    + "other default when a server is saved with this flag set.")
    private Boolean isDefault;

    /**
     * Framework active control: reads are auto-filtered to {@code active = true},
     * so a disabled row is retired from every list and every resolution without
     * being deleted (rows referenced by received mail stay intact). Filter on
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
