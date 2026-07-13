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
    api_secret             TEXT                  COMMENT 'Secondary credential (authToken / apiSecret / accessKeySecret) — stored encrypted, mark MetaField.encrypted=true',
    api_endpoint           VARCHAR(500)          COMMENT 'Provider API base URL (null = adapter default)',
    account_id             VARCHAR(255)          COMMENT 'Extra provider identifier',
    ext_config             TEXT                  COMMENT 'Provider-specific JSON config (regionId, appId, etc.)',
    sender_number          VARCHAR(50)           COMMENT 'Sender phone number (E.164 format)',
    sender_id              VARCHAR(50)           COMMENT 'Alphanumeric sender ID',
    rate_limit_per_minute  INT                   COMMENT 'Max SMS per minute',
    daily_send_limit       INT                   COMMENT 'Max SMS per day (null = unlimited)',
    is_default             TINYINT(1)   DEFAULT 0 COMMENT 'Default config for this tenant',
    is_enabled             TINYINT(1)   DEFAULT 1 COMMENT 'Whether this config is active',
    priority               INT          DEFAULT 100 COMMENT 'Lower = higher priority. Selection ordering among isDefault providers + UI list order.',
    created_time           DATETIME              COMMENT 'Created time',
    updated_time           DATETIME              COMMENT 'Updated time',
    created_id             BIGINT                COMMENT 'Created by user ID',
    created_by             VARCHAR(100)          COMMENT 'Created by username',
    updated_id             BIGINT                COMMENT 'Updated by user ID',
    updated_by             VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_sms_provider_cfg_default (tenant_id, is_default)
) COMMENT = 'SMS provider configuration';

CREATE TABLE IF NOT EXISTS sms_provider_region
(
    id                 BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id          BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform routing; >0=tenant override',
    provider_config_id BIGINT       NOT NULL COMMENT 'FK → sms_provider_config.id',
    region_code        VARCHAR(2)   NOT NULL COMMENT 'ISO 3166-1 alpha-2 (CN/TW/HK/MO/US/...); concept FK to country_region.code; "*" or invalid values rejected at app layer',
    dial_code          VARCHAR(8)   NOT NULL DEFAULT '' COMMENT 'ITU-T E.164 dial code (no leading +); denormalized from country_region.dial_code by service layer at write time',
    priority           INT          NOT NULL DEFAULT 100 COMMENT 'Lower = higher provider-selection priority within the same region',
    is_enabled         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Row-level enable switch',
    created_time       DATETIME              COMMENT 'Created time',
    updated_time       DATETIME              COMMENT 'Updated time',
    created_id         BIGINT                COMMENT 'Created by user ID',
    created_by         VARCHAR(100)          COMMENT 'Created by username',
    updated_id         BIGINT                COMMENT 'Updated by user ID',
    updated_by         VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_tenant_provider_region (tenant_id, provider_config_id, region_code),
    INDEX idx_region_enabled (region_code, is_enabled)
) COMMENT = 'Per-country SMS provider routing. Absence of any row for region X = provider does NOT serve X. Catchall via SmsProviderConfig.isDefault, NOT a magic region_code.';

CREATE TABLE IF NOT EXISTS sms_template
(
    id                   BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id            BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    code                 VARCHAR(100) NOT NULL COMMENT 'Template code for programmatic lookup, e.g. VERIFY_CODE',
    name                 VARCHAR(100) NOT NULL COMMENT 'Display name',
    description          VARCHAR(500)          COMMENT 'Description',
    content              TEXT                  COMMENT 'SMS body template with {{ variable }} placeholders',
    is_enabled           TINYINT(1)   DEFAULT 1 COMMENT 'Whether this template is active',
    created_time         DATETIME              COMMENT 'Created time',
    updated_time         DATETIME              COMMENT 'Updated time',
    created_id           BIGINT                COMMENT 'Created by user ID',
    created_by           VARCHAR(100)          COMMENT 'Created by username',
    updated_id           BIGINT                COMMENT 'Updated by user ID',
    updated_by           VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_sms_template_tenant_code (tenant_id, code)
) COMMENT = 'SMS templates with platform/tenant-level scoping';

CREATE TABLE IF NOT EXISTS sms_template_provider_binding
(
    id                   BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id            BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    template_id          BIGINT       NOT NULL COMMENT 'FK → sms_template.id',
    provider_config_id   BIGINT       NOT NULL COMMENT 'FK → sms_provider_config.id',
    region_code          VARCHAR(2)   NOT NULL DEFAULT '' COMMENT 'Optional ISO 3166-1 alpha-2 region override; blank = generic binding for this provider',
    external_template_id VARCHAR(100)          COMMENT 'Provider-registered template ID (e.g. Aliyun SMS_12345678)',
    sign_name            VARCHAR(50)           COMMENT 'SMS signature for this provider',
    priority             INT          DEFAULT 0 COMMENT 'Template-aware provider selection priority (lower = preferred)',
    is_enabled           TINYINT(1)   DEFAULT 1 COMMENT 'Whether this binding is active',
    created_time         DATETIME              COMMENT 'Created time',
    updated_time         DATETIME              COMMENT 'Updated time',
    created_id           BIGINT                COMMENT 'Created by user ID',
    created_by           VARCHAR(100)          COMMENT 'Created by username',
    updated_id           BIGINT                COMMENT 'Updated by user ID',
    updated_by           VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_tenant_tmpl_provider_region (tenant_id, template_id, provider_config_id, region_code),
    INDEX idx_template_priority (template_id, priority),
    INDEX idx_template_region (template_id, region_code)
) COMMENT = 'SMS template to provider config binding for provider-specific template IDs and signatures';

CREATE TABLE IF NOT EXISTS sms_send_record
(
    id                         BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id                  BIGINT       NOT NULL DEFAULT 0 COMMENT '0=platform, >0=tenant',
    provider_config_id         BIGINT                COMMENT 'FK → sms_provider_config.id',
    provider_type              VARCHAR(20)           COMMENT 'Provider type used for this send (Twilio/Infobip/Aliyun/etc.)',
    phone_number               VARCHAR(50)           COMMENT 'Recipient phone number',
    template_code              VARCHAR(100)          COMMENT 'Template code if sent via template',
    content                    TEXT                  COMMENT 'Rendered SMS content',
    sign_name                  VARCHAR(64)           COMMENT 'SMS signature actually used at send time (persisted for retry fidelity)',
    external_template_id       VARCHAR(100)          COMMENT 'Provider template ID actually used at send time (persisted for retry fidelity)',
    status                     VARCHAR(20)  NOT NULL DEFAULT 'Pending' COMMENT 'Pending/Sending/Sent/Failed/Retry/DeadLetter',
    retry_count                INT          DEFAULT 0 COMMENT 'Number of send attempts',
    version                    BIGINT       NOT NULL DEFAULT 0 COMMENT 'Optimistic-lock version; CAS-incremented on every state transition',
    next_retry_at              DATETIME              COMMENT 'Earliest time at which the next retry should be attempted',
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
    INDEX idx_sms_send_tenant_status (tenant_id, status),
    INDEX idx_sms_send_sent_at (sent_at),
    INDEX idx_provider_msg_id (provider_message_id)
) COMMENT = 'SMS send audit log';

-- ============================================================
-- Indexes for hot operational queries on sms_send_record.
-- See message-starter.sql for rationale — same Zombie /
-- retry-pickup contract applies here.
-- (No idx_message_id needed: provider_message_id is already
-- indexed via idx_provider_msg_id.)
-- ============================================================
CREATE INDEX idx_sms_send_status_updated ON sms_send_record (status, updated_time);
CREATE INDEX idx_sms_send_status_retry   ON sms_send_record (status, next_retry_at);
