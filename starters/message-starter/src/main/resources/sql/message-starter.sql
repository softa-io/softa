-- ============================================================
-- message-starter DDL
-- tenant_id = 0  → platform-level config (managed by ops/scripts)
-- tenant_id > 0  → tenant-level config (ORM auto-fills)
-- ============================================================

CREATE TABLE IF NOT EXISTS mail_send_server_config
(
    id                    BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id             BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    name                  VARCHAR(100) NOT NULL COMMENT 'Config name',
    description           VARCHAR(500)          COMMENT 'Description',
    protocol              VARCHAR(10)  NOT NULL COMMENT 'SMTP or SMTPS',
    host                  VARCHAR(255) NOT NULL COMMENT 'SMTP server hostname or IP',
    port                  INT          NOT NULL COMMENT 'SMTP server port',
    ssl_enabled           TINYINT(1)   DEFAULT 0 COMMENT 'Enable SSL/TLS',
    starttls_enabled      TINYINT(1)   DEFAULT 0 COMMENT 'Enable STARTTLS upgrade',
    connection_timeout_ms INT          DEFAULT 5000 COMMENT 'Connection timeout ms',
    read_timeout_ms       INT          DEFAULT 30000 COMMENT 'Read timeout ms',
    max_connections       INT          DEFAULT 10 COMMENT 'Max connections in pool',
    auth_type             VARCHAR(10)  NOT NULL DEFAULT 'Password' COMMENT 'Password or OAuth2',
    username              VARCHAR(255)          COMMENT 'Auth username',
    password              VARCHAR(500)          COMMENT 'Password',
    from_address          VARCHAR(255)          COMMENT 'Envelope from address',
    from_name             VARCHAR(100)          COMMENT 'From display name',
    reply_to_address      VARCHAR(255)          COMMENT 'Reply-to address',
    daily_send_limit      INT                   COMMENT 'Max emails per day (null=unlimited)',
    rate_limit_per_minute INT                   COMMENT 'Max emails per minute',
    read_receipt_enabled   TINYINT(1)   DEFAULT 0 COMMENT 'Request read receipts by default',
    max_retry_count        INT          DEFAULT 0 COMMENT 'Max retry attempts on send failure (0=no retry)',
    retry_interval_seconds INT          DEFAULT 60 COMMENT 'Delay in seconds between retry attempts',
    debug_enabled          TINYINT(1)   DEFAULT 0 COMMENT 'Enable SMTP protocol debug logging',
    is_default            TINYINT(1)   DEFAULT 0 COMMENT 'Default sending config for this tenant',
    is_enabled            TINYINT(1)   DEFAULT 1 COMMENT 'Whether this config is active',
    sort_order            INT          DEFAULT 0 COMMENT 'Sort order for multiple configs',
    created_time          DATETIME              COMMENT 'Created time',
    updated_time          DATETIME              COMMENT 'Updated time',
    created_id            BIGINT                COMMENT 'Created by user ID',
    created_by            VARCHAR(100)          COMMENT 'Created by username',
    updated_id            BIGINT                COMMENT 'Updated by user ID',
    updated_by            VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_tenant_default (tenant_id, is_default),
    INDEX idx_tenant (tenant_id)
) COMMENT = 'Outgoing mail server configuration (SMTP/SMTPS)';

CREATE TABLE IF NOT EXISTS mail_receive_server_config
(
    id                    BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id             BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    name                  VARCHAR(100) NOT NULL COMMENT 'Config name',
    description           VARCHAR(500)          COMMENT 'Description',
    protocol              VARCHAR(10)  NOT NULL COMMENT 'IMAP, IMAPS, POP3, POP3S',
    host                  VARCHAR(255) NOT NULL COMMENT 'Mail server hostname or IP',
    port                  INT          NOT NULL COMMENT 'Mail server port',
    ssl_enabled           TINYINT(1)   DEFAULT 0 COMMENT 'Enable SSL/TLS',
    connection_timeout_ms INT          DEFAULT 5000 COMMENT 'Connection timeout ms',
    read_timeout_ms       INT          DEFAULT 30000 COMMENT 'Read timeout ms',
    max_connections       INT          DEFAULT 10 COMMENT 'Max connections in pool',
    auth_type             VARCHAR(10)  NOT NULL DEFAULT 'Password' COMMENT 'Password or OAuth2',
    username              VARCHAR(255)          COMMENT 'Auth username',
    password              VARCHAR(500)          COMMENT 'Password',
    inbox_folder          VARCHAR(100) DEFAULT 'INBOX' COMMENT 'Folder to poll',
    fetch_folders         VARCHAR(255) DEFAULT 'INBOX' COMMENT 'Comma-separated list of folders to fetch from',
    delete_after_fetch    TINYINT(1)   DEFAULT 0 COMMENT 'Delete from server after fetch (POP3 only)',
    scheduled_fetch_enabled TINYINT(1) DEFAULT 0 COMMENT 'Enable scheduled mail fetching',
    fetch_cron_expression   VARCHAR(50) DEFAULT NULL COMMENT 'Cron expression for scheduled fetch',
    is_default            TINYINT(1)   DEFAULT 0 COMMENT 'Default receiving config for this tenant',
    is_enabled            TINYINT(1)   DEFAULT 1 COMMENT 'Whether this config is active',
    sort_order            INT          DEFAULT 0 COMMENT 'Sort order for multiple configs',
    created_time          DATETIME              COMMENT 'Created time',
    updated_time          DATETIME              COMMENT 'Updated time',
    created_id            BIGINT                COMMENT 'Created by user ID',
    created_by            VARCHAR(100)          COMMENT 'Created by username',
    updated_id            BIGINT                COMMENT 'Updated by user ID',
    updated_by            VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_tenant_default (tenant_id, is_default),
    INDEX idx_tenant (tenant_id)
) COMMENT = 'Incoming mail server configuration (IMAP/IMAPS/POP3/POP3S)';

CREATE TABLE IF NOT EXISTS mail_server_oauth2_config
(
    id                     BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id              BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    server_config_id       BIGINT       NOT NULL COMMENT 'FK → mail_send/receive_server_config.id',
    server_type            VARCHAR(10)  NOT NULL COMMENT 'Send or Receive (identifies which table)',
    provider               VARCHAR(20)  NOT NULL COMMENT 'Google / Microsoft / Custom',
    client_id              VARCHAR(255) NOT NULL COMMENT 'OAuth2 client ID',
    client_secret          VARCHAR(500) NOT NULL COMMENT 'OAuth2 client secret',
    azure_tenant_id        VARCHAR(100)          COMMENT 'Azure tenant ID (Microsoft only)',
    scope                  VARCHAR(500)          COMMENT 'OAuth2 scopes (space-separated)',
    authorization_endpoint VARCHAR(500)          COMMENT 'Authorization endpoint URL',
    token_endpoint         VARCHAR(500)          COMMENT 'Token endpoint URL',
    redirect_uri           VARCHAR(500)          COMMENT 'Redirect URI',
    created_time           DATETIME              COMMENT 'Created time',
    updated_time           DATETIME              COMMENT 'Updated time',
    created_id             BIGINT                COMMENT 'Created by user ID',
    created_by             VARCHAR(100)          COMMENT 'Created by username',
    updated_id             BIGINT                COMMENT 'Updated by user ID',
    updated_by             VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_server (server_config_id, server_type)
) COMMENT = 'OAuth2 credentials for mail server configs';

CREATE TABLE IF NOT EXISTS mail_server_oauth2_token
(
    id                   BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id            BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    server_config_id     BIGINT       NOT NULL COMMENT 'FK → mail_server_oauth2_config.id',
    account_identifier   VARCHAR(255) NOT NULL COMMENT 'Email account (usually the email address)',
    access_token         TEXT                  COMMENT 'Access token',
    refresh_token        TEXT                  COMMENT 'Refresh token',
    access_token_expiry  DATETIME              COMMENT 'Access token expiry',
    refresh_token_expiry DATETIME              COMMENT 'Refresh token expiry',
    created_time         DATETIME              COMMENT 'Created time',
    updated_time         DATETIME              COMMENT 'Updated time',
    created_id           BIGINT                COMMENT 'Created by user ID',
    created_by           VARCHAR(100)          COMMENT 'Created by username',
    updated_id           BIGINT                COMMENT 'Updated by user ID',
    updated_by           VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_account (server_config_id, account_identifier)
) COMMENT = 'OAuth2 token storage per mail account';

CREATE TABLE IF NOT EXISTS mail_send_record
(
    id               BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    server_config_id BIGINT                COMMENT 'FK → mail_send_server_config.id',
    from_address     VARCHAR(255)          COMMENT 'Sender address',
    to_addresses     TEXT                  COMMENT 'Recipients (JSON array)',
    cc_addresses     TEXT                  COMMENT 'CC (JSON array)',
    bcc_addresses    TEXT                  COMMENT 'BCC (JSON array)',
    subject          VARCHAR(500)          COMMENT 'Email subject',
    content_type     VARCHAR(10)  DEFAULT 'HTML' COMMENT 'TEXT or HTML',
    body_preview     VARCHAR(500)          COMMENT 'First 500 chars of body',
    body             MEDIUMTEXT            COMMENT 'Full email body for retry fidelity',
    has_attachments  TINYINT(1)   DEFAULT 0 COMMENT 'Has attachments',
    read_receipt_requested TINYINT(1) DEFAULT 0 COMMENT 'Whether read receipt was requested',
    priority         VARCHAR(10)           COMMENT 'Priority: High / Normal / Low',
    status           VARCHAR(10)  NOT NULL DEFAULT 'Pending' COMMENT 'Pending/Sent/Failed/Retry',
    retry_count      INT          DEFAULT 0 COMMENT 'Number of send attempts',
    read_receipt_received    TINYINT(1)   DEFAULT 0 COMMENT 'Whether read receipt has been received',
    read_receipt_received_at DATETIME              COMMENT 'Timestamp when read receipt was received',
    bounced          TINYINT(1)   DEFAULT 0 COMMENT 'Whether this email bounced',
    bounce_code      VARCHAR(20)           COMMENT 'Bounce code summary, e.g. 550 5.1.1',
    error_message    TEXT                  COMMENT 'Error detail on failure',
    sent_at          DATETIME              COMMENT 'Timestamp accepted by SMTP server',
    message_id       VARCHAR(255)          COMMENT 'SMTP Message-ID header',
    created_time     DATETIME              COMMENT 'Created time',
    updated_time     DATETIME              COMMENT 'Updated time',
    created_id       BIGINT                COMMENT 'Created by user ID',
    created_by       VARCHAR(100)          COMMENT 'Created by username',
    updated_id       BIGINT                COMMENT 'Updated by user ID',
    updated_by       VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_tenant_status (tenant_id, status),
    INDEX idx_sent_at (sent_at)
) COMMENT = 'Outgoing mail audit log';

-- ============================================================
-- Inbox: in-app notifications and todo items
-- ============================================================

CREATE TABLE IF NOT EXISTS inbox_notification
(
    id                BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id         BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    recipient_id      BIGINT       NOT NULL COMMENT 'Recipient user ID',
    title             VARCHAR(200) NOT NULL COMMENT 'Notification title',
    content           TEXT                  COMMENT 'Notification body content',
    notification_type VARCHAR(20)  NOT NULL DEFAULT 'System' COMMENT 'System / Workflow / Manual',
    source_type       VARCHAR(50)           COMMENT 'Originating object type, e.g. FLOW_INSTANCE (nullable)',
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
    INDEX idx_tenant (tenant_id)
) COMMENT = 'In-app notifications delivered to specific users';

CREATE TABLE IF NOT EXISTS inbox_todo
(
    id           BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    assignee_id  BIGINT       NOT NULL COMMENT 'Assignee user ID',
    title        VARCHAR(200) NOT NULL COMMENT 'Todo title',
    description  TEXT                  COMMENT 'Detailed description of the required action',
    source_type  VARCHAR(50)           COMMENT 'Originating object type, e.g. FLOW_INSTANCE (nullable)',
    source_id    BIGINT                COMMENT 'Originating object ID (nullable)',
    action_url   VARCHAR(500)          COMMENT 'Deep-link URL to the handling page (nullable)',
    status       VARCHAR(20)  NOT NULL DEFAULT 'Pending' COMMENT 'Pending / Done / Rejected / Expired',
    due_at       DATETIME              COMMENT 'Optional due date',
    completed_at DATETIME              COMMENT 'Timestamp when completed or rejected',
    created_time DATETIME              COMMENT 'Created time',
    updated_time DATETIME              COMMENT 'Updated time',
    created_id   BIGINT                COMMENT 'Created by user ID',
    created_by   VARCHAR(100)          COMMENT 'Created by username',
    updated_id   BIGINT                COMMENT 'Updated by user ID',
    updated_by   VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_assignee_status (assignee_id, status),
    INDEX idx_tenant (tenant_id)
) COMMENT = 'Actionable todo items assigned to specific users';

CREATE TABLE IF NOT EXISTS mail_template
(
    id           BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    code         VARCHAR(100) NOT NULL COMMENT 'Template code for programmatic lookup, e.g. USER_WELCOME',
    name         VARCHAR(100) NOT NULL COMMENT 'Display name',
    description  VARCHAR(500)          COMMENT 'Description',
    language     VARCHAR(20)  NOT NULL DEFAULT 'default' COMMENT 'BCP-47 language tag (e.g. en-US, zh-CN) or "default" as language-agnostic fallback',
    subject      VARCHAR(500)          COMMENT 'Email subject template with {{ variable }} placeholders',
    default_priority VARCHAR(10)        COMMENT 'Template default priority (High/Normal/Low)',
    body         MEDIUMTEXT            COMMENT 'HTML email body template with {{ variable }} placeholders',
    include_plain_text TINYINT(1) DEFAULT 1 COMMENT 'Whether to also include a plain-text version (auto-stripped from the rendered HTML)',
    is_enabled   TINYINT(1)   DEFAULT 1 COMMENT 'Whether this template is active',
    created_time DATETIME              COMMENT 'Created time',
    updated_time DATETIME              COMMENT 'Updated time',
    created_id   BIGINT                COMMENT 'Created by user ID',
    created_by   VARCHAR(100)          COMMENT 'Created by username',
    updated_id   BIGINT                COMMENT 'Updated by user ID',
    updated_by   VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_tenant_code_lang (tenant_id, code, language),
    INDEX idx_tenant (tenant_id)
) COMMENT = 'Email templates with multi-language and platform/tenant-level scoping';

CREATE TABLE IF NOT EXISTS mail_receive_record
(
    id               BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    server_config_id BIGINT                COMMENT 'FK → mail_receive_server_config.id',
    message_id       VARCHAR(255)          COMMENT 'IMAP UID / POP3 UIDL from server',
    mail_type        VARCHAR(20)  DEFAULT 'NORMAL' COMMENT 'Mail type: NORMAL / READ_RECEIPT / BOUNCE',
    original_message_id VARCHAR(255)       COMMENT 'Original sent Message-ID (for receipt/bounce linking)',
    from_address     VARCHAR(255)          COMMENT 'Sender address',
    to_addresses     TEXT                  COMMENT 'Recipients (JSON array)',
    cc_addresses     TEXT                  COMMENT 'CC (JSON array)',
    subject          VARCHAR(500)          COMMENT 'Email subject',
    content_type     VARCHAR(10)           COMMENT 'TEXT or HTML',
    body             MEDIUMTEXT            COMMENT 'Full email body',
    has_attachments  TINYINT(1)   DEFAULT 0 COMMENT 'Has attachments',
    attachment_names TEXT                  COMMENT 'Attachment names (JSON array)',
    status           VARCHAR(10)  NOT NULL DEFAULT 'Unread' COMMENT 'Unread/Read/Archived/Deleted',
    received_at      DATETIME              COMMENT 'Original timestamp from mail server',
    fetched_at       DATETIME              COMMENT 'Timestamp when this system fetched the email',
    folder_name      VARCHAR(100) DEFAULT 'INBOX' COMMENT 'Source folder',
    smtp_reply_code      VARCHAR(10)       COMMENT 'SMTP reply code, e.g. 550',
    enhanced_status_code VARCHAR(20)       COMMENT 'Enhanced status code, e.g. 5.1.1',
    diagnostic_message   TEXT              COMMENT 'Full bounce diagnostic message',
    failed_recipients    VARCHAR(2000)     COMMENT 'Failed recipients (JSON array)',
    eml_file_id          BIGINT            COMMENT 'EML original file ID (file-starter)',
    created_time     DATETIME              COMMENT 'Created time',
    updated_time     DATETIME              COMMENT 'Updated time',
    created_id       BIGINT                COMMENT 'Created by user ID',
    created_by       VARCHAR(100)          COMMENT 'Created by username',
    updated_id       BIGINT                COMMENT 'Updated by user ID',
    updated_by       VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_server_msg (server_config_id, message_id),
    INDEX idx_tenant_status (tenant_id, status)
) COMMENT = 'Incoming mail records fetched from IMAP/POP3';
