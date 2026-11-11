package io.softa.starter.message.mail.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * Per-(server_config, folder) high-water mark for IMAP UID-based incremental
 * fetch.
 * <p>
 * Each row tracks the last IMAP UID we successfully processed for one folder
 * on one server config, plus the {@code UIDVALIDITY} we observed at that time.
 * The cron consumer queries this watermark before pulling messages and only
 * fetches {@code UID > last_seen_uid}. After a successful batch, the row is
 * updated with the new high-water mark via a CAS-style conditional update.
 * <p>
 * The {@code in_progress_since} column doubles as a soft lease: a worker sets
 * it before fetching and clears it on completion. If a worker crashes the
 * lease becomes stale and another worker may take over after the configured
 * {@code softa.message.mail.fetch.lease-timeout} threshold.
 * <p>
 * Every lease transition (acquire / release / reset) is an optimistic-lock CAS
 * on {@code version}: a worker that was superseded by a stale-lease takeover
 * fails its late writes instead of clobbering the new holder's lease or UID.
 * <p>
 * IMAP-only — POP3 has no UID concept and is handled by destructive DELE.
 * <p>
 * Shared by the two owners' tables: {@link MailFetchImapWatermark} (a
 * tenant's receive servers) and {@link PlatformMailFetchWatermark} (the
 * platform's).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class AbstractMailFetchWatermark extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Server Config ID", required = true,
            description = "The receive server whose folder this tracks — a server of the same owner")
    private Long serverConfigId;

    @Field(required = true, length = 100,
            description = "Folder name on the IMAP server (e.g. INBOX, Junk)")
    private String folderName;

    @Field(label = "UID Validity",
            description = "IMAP UIDVALIDITY observed when last_seen_uid was set; "
                    + "if the server reports a different value on the next fetch, the UID "
                    + "space has reset (mailbox rebuild) and last_seen_uid is reset to 0.")
    private Long uidValidity;

    @Field(label = "Last Seen UID", required = true,
            description = "Highest IMAP UID processed for this (config, folder). "
                    + "Next fetch starts at last_seen_uid + 1. Advances monotonically only.")
    private Long lastSeenUid;

    @Field(description = "Timestamp of the most recent successful fetch (diagnostics).")
    private LocalDateTime lastFetchedAt;

    @Field(description = "When a worker started fetching this (config, folder). "
                    + "Set on lease acquisition, cleared on completion. Stale leases (older "
                    + "than the configured lease-timeout) are reclaimable by other workers.")
    private LocalDateTime inProgressSince;

    @Field(required = true, description = "Optimistic-lock version. Bumped on every lease transition; "
                    + "a superseded worker's late release/advance fails the CAS and no-ops.")
    private Long version;
}
