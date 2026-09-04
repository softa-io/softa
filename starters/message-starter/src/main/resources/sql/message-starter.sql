-- ============================================================
-- message-starter DDL
-- tenant_id = -1 → platform-tier row (managed by the platform operator; invisible to tenants)
-- tenant_id > 0  → tenant-level row (ORM auto-fills)
-- tenant_id = 0  → single-tenant deployments only (column default, no filtering applies)
-- ============================================================

CREATE TABLE IF NOT EXISTS mail_send_server_config
(
    id                    BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id             BIGINT       NOT NULL DEFAULT 0 COMMENT '-1=platform, >0=tenant, 0=single-tenant default',
    name                  VARCHAR(100) NOT NULL COMMENT 'Config name',
    description           VARCHAR(500)          COMMENT 'Description',
    protocol              VARCHAR(10)  NOT NULL COMMENT 'SMTP or SMTPS',
    host                  VARCHAR(255) NOT NULL COMMENT 'SMTP server hostname or IP',
    port                  INT          NOT NULL COMMENT 'SMTP server port',
    ssl_enabled           TINYINT(1)   DEFAULT 0 COMMENT 'Enable SSL/TLS',
    starttls_enabled      TINYINT(1)   DEFAULT 0 COMMENT 'Enable STARTTLS upgrade',
    username              VARCHAR(255)          COMMENT 'Auth username',
    password              VARCHAR(512)          COMMENT 'Password (AES-encrypted at rest)',
    from_address          VARCHAR(255)          COMMENT 'Envelope from address',
    from_name             VARCHAR(100)          COMMENT 'From display name',
    reply_to_address      VARCHAR(255)          COMMENT 'Reply-to address',
    daily_send_limit      INT                   COMMENT 'Max emails per day (null=unlimited)',
    rate_limit_per_minute INT                   COMMENT 'Max emails per minute',
    read_receipt_enabled  TINYINT(1)   DEFAULT 0 COMMENT 'Request read receipts by default',
    is_default            TINYINT(1)   DEFAULT 0 COMMENT 'Default sending config for this tenant',
    active                TINYINT(1)   DEFAULT 1 COMMENT 'Whether this config is active',
    sequence              INT          DEFAULT 0 COMMENT 'Display + processing order (ascending). NOT a failover priority — see entity Javadoc.',
    created_time          DATETIME              COMMENT 'Created time',
    updated_time          DATETIME              COMMENT 'Updated time',
    created_id            BIGINT                COMMENT 'Created by user ID',
    created_by            VARCHAR(100)          COMMENT 'Created by username',
    updated_id            BIGINT                COMMENT 'Updated by user ID',
    updated_by            VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_mail_send_cfg_default (tenant_id, is_default)
) COMMENT = 'Outgoing mail server configuration (SMTP/SMTPS)';

CREATE TABLE IF NOT EXISTS mail_receive_server_config
(
    id                    BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id             BIGINT       NOT NULL DEFAULT 0 COMMENT '-1=platform, >0=tenant, 0=single-tenant default',
    name                  VARCHAR(100) NOT NULL COMMENT 'Config name',
    description           VARCHAR(500)          COMMENT 'Description',
    protocol              VARCHAR(10)  NOT NULL COMMENT 'IMAP, IMAPS, POP3, POP3S',
    host                  VARCHAR(255) NOT NULL COMMENT 'Mail server hostname or IP',
    port                  INT          NOT NULL COMMENT 'Mail server port',
    ssl_enabled           TINYINT(1)   DEFAULT 0 COMMENT 'Enable SSL/TLS',
    username              VARCHAR(255)          COMMENT 'Auth username',
    password              VARCHAR(512)          COMMENT 'Password (AES-encrypted at rest)',
    fetch_folders         VARCHAR(255) DEFAULT 'INBOX' COMMENT 'Comma-separated list of folders to fetch from',
    is_default            TINYINT(1)   DEFAULT 0 COMMENT 'Default receiving config for this tenant',
    active                TINYINT(1)   DEFAULT 1 COMMENT 'Whether this config is active',
    sequence              INT          DEFAULT 0 COMMENT 'Display + processing order (ascending). NOT a failover priority — see entity Javadoc.',
    created_time          DATETIME              COMMENT 'Created time',
    updated_time          DATETIME              COMMENT 'Updated time',
    created_id            BIGINT                COMMENT 'Created by user ID',
    created_by            VARCHAR(100)          COMMENT 'Created by username',
    updated_id            BIGINT                COMMENT 'Updated by user ID',
    updated_by            VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_mail_recv_cfg_default (tenant_id, is_default)
) COMMENT = 'Incoming mail server configuration (IMAP/IMAPS/POP3/POP3S)';

CREATE TABLE IF NOT EXISTS mail_send_record
(
    id               BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '-1=platform, >0=tenant, 0=single-tenant default',
    server_config_id BIGINT                COMMENT 'FK → mail_send_server_config.id',
    from_address     VARCHAR(255)          COMMENT 'Sender address',
    to_addresses     TEXT                  COMMENT 'Recipients (MULTI_STRING: bare values joined on commas, ORM-managed)',
    cc_addresses     TEXT                  COMMENT 'CC (MULTI_STRING: bare values joined on commas)',
    bcc_addresses    TEXT                  COMMENT 'BCC (MULTI_STRING: bare values joined on commas)',
    subject          VARCHAR(500)          COMMENT 'Email subject',
    body_mode        VARCHAR(32)  NOT NULL DEFAULT 'HTML' COMMENT 'HTML / PLAIN / HTML_WITH_DERIVED_PLAIN / HTML_WITH_AUTHORED_PLAIN',
    body_html        MEDIUMTEXT            COMMENT 'HTML body verbatim; null for PLAIN mode',
    body_text        MEDIUMTEXT            COMMENT 'Plain text body verbatim; null for HTML mode',
    attachments      TEXT                  COMMENT 'Attachment fileIds (List<Long> CSV); ORM resolves to List<FileInfo> at read time. Null/empty when no attachments.',
    read_receipt_requested TINYINT(1) DEFAULT 0 COMMENT 'Whether read receipt was requested',
    priority         VARCHAR(10)           COMMENT 'Priority: HIGH / NORMAL / LOW',
    reply_to         VARCHAR(255)          COMMENT 'Reply-To address actually used (after dto > template > config fallback); nullable',
    status           VARCHAR(64)  NOT NULL DEFAULT 'Pending' COMMENT 'Pending/Sending/Sent/Failed/Retry/DeadLetter',
    retry_count      INT          DEFAULT 0 COMMENT 'Number of send attempts',
    version          BIGINT       NOT NULL DEFAULT 0 COMMENT 'Optimistic-lock version; CAS-incremented on every state transition',
    next_retry_at    DATETIME              COMMENT 'Earliest time at which the next retry should be attempted',
    read_receipt_received    TINYINT(1)   DEFAULT 0 COMMENT 'Whether read receipt has been received',
    read_receipt_received_at DATETIME              COMMENT 'Timestamp when read receipt was received',
    bounced          TINYINT(1)   DEFAULT 0 COMMENT 'Whether this email bounced',
    bounce_code      VARCHAR(20)           COMMENT 'Bounce code summary, e.g. 550 5.1.1',
    error_code       VARCHAR(100)          COMMENT 'Provider-specific error code on failure',
    error_message    TEXT                  COMMENT 'Error detail on failure',
    sent_at          DATETIME              COMMENT 'Timestamp accepted by SMTP server',
    message_id       VARCHAR(255)          COMMENT 'SMTP Message-ID header',
    created_time     DATETIME              COMMENT 'Created time',
    updated_time     DATETIME              COMMENT 'Updated time',
    created_id       BIGINT                COMMENT 'Created by user ID',
    created_by       VARCHAR(100)          COMMENT 'Created by username',
    updated_id       BIGINT                COMMENT 'Updated by user ID',
    updated_by       VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_mail_send_tenant_status (tenant_id, status),
    INDEX idx_mail_send_sent_at (sent_at)
) COMMENT = 'Outgoing mail audit log';

CREATE TABLE IF NOT EXISTS mail_fetch_imap_watermark
(
    id                 BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id          BIGINT       NOT NULL DEFAULT 0 COMMENT '-1=platform, >0=tenant, 0=single-tenant default',
    server_config_id   BIGINT       NOT NULL COMMENT 'FK → mail_receive_server_config.id',
    folder_name        VARCHAR(100) NOT NULL COMMENT 'IMAP folder name (e.g. INBOX)',
    uid_validity       BIGINT                COMMENT 'IMAP UIDVALIDITY observed when last_seen_uid was set',
    last_seen_uid      BIGINT       NOT NULL DEFAULT 0 COMMENT 'Highest IMAP UID processed; next fetch starts at +1',
    last_fetched_at    DATETIME              COMMENT 'Timestamp of the most recent successful fetch',
    in_progress_since  DATETIME              COMMENT 'Soft lease: when a worker started fetching; null when idle',
    version            BIGINT       NOT NULL DEFAULT 0 COMMENT 'Optimistic-lock version for lease-transition CAS',
    created_time       DATETIME              COMMENT 'Created time',
    updated_time       DATETIME              COMMENT 'Updated time',
    created_id         BIGINT                COMMENT 'Created by user ID',
    created_by         VARCHAR(100)          COMMENT 'Created by username',
    updated_id         BIGINT                COMMENT 'Updated by user ID',
    updated_by         VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_config_folder (server_config_id, folder_name),
    INDEX idx_watermark_tenant (tenant_id)
) COMMENT = 'Per-(server_config, folder) IMAP UID high-water mark for incremental fetch';

-- ============================================================
-- Inbox: in-app notifications
-- ============================================================

CREATE TABLE IF NOT EXISTS inbox_notification
(
    id                BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id         BIGINT       NOT NULL DEFAULT 0 COMMENT '-1=platform, >0=tenant, 0=single-tenant default',
    recipient_id      BIGINT       NOT NULL COMMENT 'Recipient user ID',
    title             VARCHAR(200) NOT NULL COMMENT 'Notification title',
    content           TEXT                  COMMENT 'Notification body content',
    notification_type VARCHAR(20)  NOT NULL DEFAULT 'System' COMMENT 'System / Workflow / Manual',
    source_model      VARCHAR(50)           COMMENT 'Originating metadata model name (matches sys_model.model_name), e.g. FlowInstance (nullable)',
    source_id         BIGINT                COMMENT 'Originating object ID (nullable)',
    is_read           TINYINT(1)   DEFAULT 0 COMMENT 'Whether the recipient has read this notification',
    read_at           DATETIME              COMMENT 'Timestamp when the notification was read',
    expired_at        DATETIME              COMMENT 'Optional expiry time',
    created_time      DATETIME              COMMENT 'Created time',
    updated_time      DATETIME              COMMENT 'Updated time',
    created_id        BIGINT                COMMENT 'Created by user ID',
    created_by        VARCHAR(100)          COMMENT 'Created by username',
    updated_id        BIGINT                COMMENT 'Updated by user ID',
    updated_by        VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_recipient_read (recipient_id, is_read),
    INDEX idx_inbox_notif_tenant (tenant_id)
) COMMENT = 'In-app notifications delivered to specific users';

CREATE TABLE IF NOT EXISTS mail_template
(
    id           BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '-1=platform, >0=tenant, 0=single-tenant default',
    code         VARCHAR(100) NOT NULL COMMENT 'Template code for programmatic lookup, e.g. USER_WELCOME',
    name         VARCHAR(100) NOT NULL COMMENT 'Display name',
    description  VARCHAR(500)          COMMENT 'Description',
    subject      VARCHAR(500) NOT NULL COMMENT 'Email subject template with {{ variable }} placeholders',
    default_priority VARCHAR(10)        COMMENT 'Template default priority (HIGH/NORMAL/LOW)',
    reply_to     VARCHAR(255)          COMMENT 'Default Reply-To address for this template; nullable',
    attachments  TEXT                  COMMENT 'Default attachment fileIds (List<Long> CSV); ORM resolves to List<FileInfo> at read time',
    preferred_server_config_id BIGINT  COMMENT 'Preferred mail send server (FK → mail_send_server_config.id); resolved as dto > template > dispatcher default',
    body_html    MEDIUMTEXT            COMMENT 'HTML body template with {{ variable }} placeholders',
    body_text    MEDIUMTEXT            COMMENT 'Plain text body template with {{ variable }} placeholders; required for PLAIN and HTML_WITH_AUTHORED_PLAIN modes',
    body_mode    VARCHAR(32)  NOT NULL DEFAULT 'HTML_WITH_DERIVED_PLAIN' COMMENT 'HTML / PLAIN / HTML_WITH_DERIVED_PLAIN / HTML_WITH_AUTHORED_PLAIN',
    active       TINYINT(1)   DEFAULT 1 COMMENT 'Whether this template is active',
    created_time DATETIME              COMMENT 'Created time',
    updated_time DATETIME              COMMENT 'Updated time',
    created_id   BIGINT                COMMENT 'Created by user ID',
    created_by   VARCHAR(100)          COMMENT 'Created by username',
    updated_id   BIGINT                COMMENT 'Updated by user ID',
    updated_by   VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_mail_template_tenant_code (tenant_id, code)
) COMMENT = 'Email templates with platform/tenant-level scoping';

-- System-maintained monthly send ledger (one row per bucket per month),
-- incremented via optimistic-lock CAS at send acceptance. Durable history:
-- rows are never expired. tenant_id = -1 is the platform's own bucket.
CREATE TABLE IF NOT EXISTS tenant_message_usage
(
    id                  BIGINT       NOT NULL PRIMARY KEY COMMENT 'Distributed ID',
    tenant_id           BIGINT       NOT NULL COMMENT 'Quota bucket; -1 = the platform itself',
    month               VARCHAR(7)   NOT NULL COMMENT 'Calendar month, yyyy-MM (server default zone)',
    mail_monthly_limit  BIGINT                COMMENT 'Snapshot of the mail ceiling at the last accepted mail send; NULL = unlimited',
    mail_used           BIGINT       NOT NULL DEFAULT 0 COMMENT 'Accepted mail sends this month',
    sms_monthly_limit   BIGINT                COMMENT 'Snapshot of the SMS ceiling at the last accepted SMS send; NULL = unlimited',
    sms_used            BIGINT       NOT NULL DEFAULT 0 COMMENT 'Accepted SMS sends this month',
    version             BIGINT       NOT NULL DEFAULT 0 COMMENT 'Optimistic-lock version',
    created_time        DATETIME              COMMENT 'Created time',
    updated_time        DATETIME              COMMENT 'Updated time',
    created_id          BIGINT                COMMENT 'Created by user ID',
    created_by          VARCHAR(100)          COMMENT 'Created by username',
    updated_id          BIGINT                COMMENT 'Updated by user ID',
    updated_by          VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_tenant_message_usage_bucket (tenant_id, month)
) COMMENT = 'Monthly message send ledger per quota bucket (system-maintained)';

-- Platform-owned registry ABOUT tenants (deliberately NOT tenant-isolated):
-- monthly send ceilings, configured by platform operations only.
-- tenant_id = -1 governs the platform's own sends.
CREATE TABLE IF NOT EXISTS tenant_message_quota
(
    id                  BIGINT       NOT NULL PRIMARY KEY COMMENT 'Distributed ID',
    tenant_id           BIGINT       NOT NULL COMMENT 'Governed tenant; -1 = the platform itself',
    mail_monthly_limit  BIGINT                COMMENT 'Max accepted mail sends per calendar month; NULL = deployment default',
    sms_monthly_limit   BIGINT                COMMENT 'Max accepted SMS sends per calendar month; NULL = deployment default',
    description         VARCHAR(500)          COMMENT 'Operations note (plan/contract behind this ceiling)',
    created_time        DATETIME              COMMENT 'Created time',
    updated_time        DATETIME              COMMENT 'Updated time',
    created_id          BIGINT                COMMENT 'Created by user ID',
    created_by          VARCHAR(100)          COMMENT 'Created by username',
    updated_id          BIGINT                COMMENT 'Updated by user ID',
    updated_by          VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_tenant_message_quota_tenant (tenant_id)
) COMMENT = 'Monthly message send quotas per tenant (platform-operations managed)';

CREATE TABLE IF NOT EXISTS mail_receive_record
(
    id               BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '-1=platform, >0=tenant, 0=single-tenant default',
    server_config_id BIGINT                COMMENT 'FK → mail_receive_server_config.id',
    message_id       VARCHAR(255)          COMMENT 'RFC 5322 Message-ID header value (or synthetic SHA-256 fallback when absent); dedup key together with server_config_id',
    mail_type        VARCHAR(20)  DEFAULT 'Normal' COMMENT 'Primary content type: Normal / ReadReceipt / Bounce / AutoReply / CalendarInvite / Unknown',
    is_mailing_list  TINYINT(1)   DEFAULT 0 COMMENT 'List-Id / List-Unsubscribe / Precedence:bulk present (orthogonal to mail_type)',
    is_encrypted     TINYINT(1)   DEFAULT 0 COMMENT 'PGP-MIME or S/MIME enveloped-data envelope (orthogonal to mail_type)',
    is_spam          TINYINT(1)   DEFAULT 0 COMMENT 'Anti-spam markers present (X-Spam-Flag/X-Spam-Status/Exchange SCL ≥ 5)',
    original_message_id VARCHAR(255)       COMMENT 'Original sent Message-ID (for receipt/bounce linking)',
    from_address     VARCHAR(255)          COMMENT 'Sender address',
    to_addresses     TEXT                  COMMENT 'Recipients (MULTI_STRING: bare values joined on commas, ORM-managed)',
    cc_addresses     TEXT                  COMMENT 'CC (MULTI_STRING: bare values joined on commas)',
    subject          VARCHAR(500)          COMMENT 'Email subject',
    body_text        MEDIUMTEXT            COMMENT 'Plain text for preview/search; verbatim when sender shipped text/plain, derived from body_html at write time when HTML-only. Use body_mode to tell which.',
    body_html        MEDIUMTEXT            COMMENT 'Sender-authored HTML part verbatim; null when the email has no text/html part',
    body_mode        VARCHAR(20)           COMMENT 'Wire MIME shape captured pre-derivation: HTML / PLAIN / HTML_WITH_PLAIN_ALT; null for pure-attachment emails',
    attachments      TEXT                  COMMENT 'Attachment fileIds (List<Long> CSV); ORM resolves to List<FileInfo> at read time',
    status           VARCHAR(10)  NOT NULL DEFAULT 'Unread' COMMENT 'Unread/Read/Archived/Deleted',
    received_at      DATETIME              COMMENT 'Original timestamp from mail server',
    fetched_at       DATETIME              COMMENT 'Timestamp when this system fetched the email',
    folder_name      VARCHAR(100) DEFAULT 'INBOX' COMMENT 'Source folder',
    smtp_reply_code      VARCHAR(10)       COMMENT 'SMTP reply code, e.g. 550',
    enhanced_status_code VARCHAR(20)       COMMENT 'Enhanced status code, e.g. 5.1.1',
    diagnostic_message   TEXT              COMMENT 'Full bounce diagnostic message',
    failed_recipients    TEXT              COMMENT 'Failed recipient addresses (MULTI_STRING: bare values joined on commas)',
    eml_file_id          BIGINT            COMMENT 'EML original file ID (file-starter)',
    truncation_reason    VARCHAR(32)       COMMENT 'BodyTooLarge / AttachmentTooLarge / MimeDepthExceeded / MimePartsExceeded / ParseFailed; null when fully processed',
    created_time     DATETIME              COMMENT 'Created time',
    updated_time     DATETIME              COMMENT 'Updated time',
    created_id       BIGINT                COMMENT 'Created by user ID',
    created_by       VARCHAR(100)          COMMENT 'Created by username',
    updated_id       BIGINT                COMMENT 'Updated by user ID',
    updated_by       VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_server_msg (server_config_id, message_id),
    INDEX idx_mail_recv_tenant_status (tenant_id, status)
) COMMENT = 'Incoming mail records fetched from IMAP/POP3';

-- ============================================================
-- Indexes for hot operational queries on mail_send_record.
-- Kept as standalone CREATE INDEX statements (not inline in the
-- CREATE TABLE above) so this file can be re-applied to
-- already-deployed environments via tooling that diffs DDL.
--
-- Apply once per environment. MySQL 8.0+ supports online index
-- creation: rerun with `ALGORITHM=INPLACE, LOCK=NONE` in a
-- migration script if data already exists.
--
-- idx_mail_send_status_updated — ZombieRecordSweeper sweeps every
--   minute with `WHERE status='Sending' AND updated_time < ?`.
--   Without this index it degrades to a table scan once the
--   table grows to seven figures.
-- idx_mail_send_status_retry   — manual / monitoring queries that
--   pull stuck rows: `WHERE status IN ('Pending','Retry') AND
--   next_retry_at <= ?`.
-- idx_message_id          — bounce + read-receipt linking via
--   MailSendRecordServiceImpl#findByMessageId; called once per
--   inbound classified mail. Hot during bounce storms.
-- ============================================================
CREATE INDEX idx_mail_send_status_updated ON mail_send_record (status, updated_time);
CREATE INDEX idx_mail_send_status_retry   ON mail_send_record (status, next_retry_at);
CREATE INDEX idx_message_id          ON mail_send_record (message_id);

-- ============================================================
-- Indexes for operational queries on mail_receive_record.
--
-- idx_truncation — operations / SecOps surface for "show me all
--   degraded emails today": `WHERE truncation_reason IS NOT NULL`.
--   The column is NULL for the overwhelming majority of rows, so
--   the index stays small and the IS NOT NULL scan is cheap.
-- ============================================================
CREATE INDEX idx_truncation ON mail_receive_record (truncation_reason);
