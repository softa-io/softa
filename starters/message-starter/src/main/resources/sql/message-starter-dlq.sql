-- ============================================================
-- message-starter dlq DDL
-- ============================================================

CREATE TABLE IF NOT EXISTS dead_letter_message
(
    id                BIGINT        NOT NULL PRIMARY KEY COMMENT 'ID',
    source_tenant_id  BIGINT                 COMMENT 'Source Tenant Id',
    source            VARCHAR(20)   NOT NULL DEFAULT 'BrokerPoison' COMMENT 'BrokerPoison (Pulsar DLQ) or SendExhausted (mail/sms retry exhausted)',
    original_topic    VARCHAR(128)  NOT NULL DEFAULT '' COMMENT 'Original Topic',
    dlq_topic         VARCHAR(128)  NOT NULL DEFAULT '' COMMENT 'DLQ Topic',
    subscription_name VARCHAR(128)           COMMENT 'Subscription Name',
    event_type        VARCHAR(64)            COMMENT 'Event Type',
    event_id          BIGINT                 COMMENT 'Event Id',
    payload           TEXT                   COMMENT 'Payload',
    status            VARCHAR(20)   NOT NULL DEFAULT 'Pending' COMMENT 'Status',
    last_error_msg    VARCHAR(3000)          COMMENT 'Last Error Msg',
    resolved_remark   VARCHAR(3000)          COMMENT 'Resolved Remark',
    created_id        BIGINT                 COMMENT 'Created ID',
    created_time      DATETIME               COMMENT 'Created Time',
    created_by        VARCHAR(64)            COMMENT 'Created By',
    updated_id        BIGINT                 COMMENT 'Updated ID',
    updated_time      DATETIME               COMMENT 'Updated Time',
    updated_by        VARCHAR(64)            COMMENT 'Updated By',
    INDEX idx_status_created (status, created_time),
    INDEX idx_source_status (source, status),
    INDEX idx_original_topic (original_topic)
) COMMENT = 'Dead Letter Message';
