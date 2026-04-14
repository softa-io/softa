-- ============================================================
-- message-starter SMS DDL
-- tenant_id = 0  → platform-level config (managed by ops/scripts)
-- tenant_id > 0  → tenant-level config (ORM auto-fills)
-- ============================================================

CREATE TABLE IF NOT EXISTS sms_provider_config
(
    id                     BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id              BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    name                   VARCHAR(100) NOT NULL COMMENT 'Config name',
    description            VARCHAR(500)          COMMENT 'Description',
    provider_type          VARCHAR(20)  NOT NULL COMMENT 'Twilio / Infobip / Aliyun / Tencent / Custom',
    api_key                VARCHAR(255)          COMMENT 'Primary credential (accountSid / apiKey / accessKeyId)',
    api_secret             VARCHAR(500)          COMMENT 'Secondary credential (authToken / apiSecret / accessKeySecret)',
    api_endpoint           VARCHAR(500)          COMMENT 'Provider API base URL (null = adapter default)',
    account_id             VARCHAR(255)          COMMENT 'Extra provider identifier',
    ext_config             TEXT                  COMMENT 'Provider-specific JSON config (regionId, appId, etc.)',
    sender_number          VARCHAR(50)           COMMENT 'Sender phone number (E.164 format)',
    sender_id              VARCHAR(50)           COMMENT 'Alphanumeric sender ID',
    rate_limit_per_minute  INT                   COMMENT 'Max SMS per minute',
    daily_send_limit       INT                   COMMENT 'Max SMS per day (null = unlimited)',
    is_default             TINYINT(1)   DEFAULT 0 COMMENT 'Default config for this tenant',
    is_enabled             TINYINT(1)   DEFAULT 1 COMMENT 'Whether this config is active',
    sort_order             INT          DEFAULT 0 COMMENT 'Sort order for multiple configs',
    max_retry_count        INT          DEFAULT 0 COMMENT 'Max retry attempts on send failure (0 = no retry)',
    retry_interval_seconds INT          DEFAULT 60 COMMENT 'Delay in seconds between retry attempts',
    created_time           DATETIME              COMMENT 'Created time',
    updated_time           DATETIME              COMMENT 'Updated time',
    created_id             BIGINT                COMMENT 'Created by user ID',
    created_by             VARCHAR(100)          COMMENT 'Created by username',
    updated_id             BIGINT                COMMENT 'Updated by user ID',
    updated_by             VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_tenant_default (tenant_id, is_default),
    INDEX idx_tenant (tenant_id)
) COMMENT = 'SMS provider configuration';

CREATE TABLE IF NOT EXISTS sms_template
(
    id                   BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id            BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    code                 VARCHAR(100) NOT NULL COMMENT 'Template code for programmatic lookup, e.g. VERIFY_CODE',
    name                 VARCHAR(100) NOT NULL COMMENT 'Display name',
    description          VARCHAR(500)          COMMENT 'Description',
    language             VARCHAR(20)  NOT NULL DEFAULT 'default' COMMENT 'BCP-47 language tag (e.g. en-US, zh-CN) or "default"',
    content              TEXT                  COMMENT 'SMS body template with {{ variable }} placeholders',
    external_template_id VARCHAR(100)          COMMENT 'Pre-registered template ID for providers like Aliyun/Tencent',
    sign_name            VARCHAR(50)           COMMENT 'SMS signature (required by Chinese providers)',
    provider_config_id   BIGINT                COMMENT 'Bound provider config ID; template sends use this provider if set',
    is_enabled           TINYINT(1)   DEFAULT 1 COMMENT 'Whether this template is active',
    created_time         DATETIME              COMMENT 'Created time',
    updated_time         DATETIME              COMMENT 'Updated time',
    created_id           BIGINT                COMMENT 'Created by user ID',
    created_by           VARCHAR(100)          COMMENT 'Created by username',
    updated_id           BIGINT                COMMENT 'Updated by user ID',
    updated_by           VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_tenant_code_lang (tenant_id, code, language),
    INDEX idx_tenant (tenant_id)
) COMMENT = 'SMS templates with multi-language and platform/tenant-level scoping';

CREATE TABLE IF NOT EXISTS sms_template_provider_binding
(
    id                   BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id            BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    template_id          BIGINT       NOT NULL COMMENT 'FK → sms_template.id',
    provider_config_id   BIGINT       NOT NULL COMMENT 'FK → sms_provider_config.id',
    external_template_id VARCHAR(100)          COMMENT 'Provider-registered template ID (e.g. Aliyun SMS_12345678)',
    sign_name            VARCHAR(50)           COMMENT 'SMS signature for this provider',
    sort_order           INT          DEFAULT 0 COMMENT 'Failover priority (lower = tried first)',
    is_enabled           TINYINT(1)   DEFAULT 1 COMMENT 'Whether this binding is active',
    created_time         DATETIME              COMMENT 'Created time',
    updated_time         DATETIME              COMMENT 'Updated time',
    created_id           BIGINT                COMMENT 'Created by user ID',
    created_by           VARCHAR(100)          COMMENT 'Created by username',
    updated_id           BIGINT                COMMENT 'Updated by user ID',
    updated_by           VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_tenant_tmpl_provider (tenant_id, template_id, provider_config_id),
    INDEX idx_template_sort (template_id, sort_order)
) COMMENT = 'SMS template to provider config binding for cross-channel failover';

CREATE TABLE IF NOT EXISTS sms_send_record
(
    id                         BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id                  BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    provider_config_id         BIGINT                COMMENT 'FK → sms_provider_config.id',
    provider_type              VARCHAR(20)           COMMENT 'Provider type used for this send (Twilio/Infobip/Aliyun/etc.)',
    phone_number               VARCHAR(50)           COMMENT 'Recipient phone number',
    template_code              VARCHAR(100)          COMMENT 'Template code if sent via template',
    content                    TEXT                  COMMENT 'Rendered SMS content',
    content_preview            VARCHAR(200)          COMMENT 'First 200 chars of content',
    status                     VARCHAR(10)  NOT NULL DEFAULT 'Pending' COMMENT 'Pending/Sent/Failed/Retry',
    retry_count                INT          DEFAULT 0 COMMENT 'Number of send attempts',
    error_message              TEXT                  COMMENT 'Error detail on failure',
    error_code                 VARCHAR(100)          COMMENT 'Provider-specific error code on failure',
    provider_message_id        VARCHAR(255)          COMMENT 'External message ID from SMS provider',
    sent_at                    DATETIME              COMMENT 'Timestamp when sent',
    delivery_status            VARCHAR(20)  DEFAULT 'Unknown' COMMENT 'Unknown/Delivered/Undelivered/Failed',
    delivery_status_updated_at DATETIME              COMMENT 'Last delivery status update time',
    created_time               DATETIME              COMMENT 'Created time',
    updated_time               DATETIME              COMMENT 'Updated time',
    created_id                 BIGINT                COMMENT 'Created by user ID',
    created_by                 VARCHAR(100)          COMMENT 'Created by username',
    updated_id                 BIGINT                COMMENT 'Updated by user ID',
    updated_by                 VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_tenant_status (tenant_id, status),
    INDEX idx_sent_at (sent_at),
    INDEX idx_provider_msg_id (provider_message_id)
) COMMENT = 'SMS send audit log';
