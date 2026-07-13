-- ============================================================
-- message-starter transactional outbox DDL
-- Used for Mail / SMS send paths: the business record (MailSendRecord /
-- SmsSendRecord) and a row in this table are written atomically in the
-- same DB transaction. OutboxPublisher (500ms poll) claims NEW rows as
-- PUBLISHING through framework versionLock, pushes them to the broker, and
-- flips them to PUBLISHED on success.
--
-- Stale PUBLISHING rows are reopened by ZombieRecordSweeper.
-- ============================================================

CREATE TABLE IF NOT EXISTS message_outbox
(
    id              BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    aggregate_type  VARCHAR(50)  NOT NULL COMMENT 'Aggregate type, e.g. MailSendRecord / SmsSendRecord',
    aggregate_id    BIGINT       NOT NULL COMMENT 'Aggregate primary key the message refers to',
    route           VARCHAR(30)  NOT NULL COMMENT 'Logical delivery route (MAIL_SEND / SMS_SEND)',
    payload         TEXT         NOT NULL COMMENT 'Message body serialized as JSON (recordId / tenantId / traceId)',
    status          VARCHAR(20)  NOT NULL DEFAULT 'New' COMMENT 'New / Publishing / Published / Dead',
    attempts        INT          NOT NULL DEFAULT 0 COMMENT 'Number of publish attempts so far',
    last_error      VARCHAR(500)          COMMENT 'Last publish error message (for dead rows)',
    next_attempt_at DATETIME              COMMENT 'Earliest time the publisher should pick this row up next',
    published_at    DATETIME              COMMENT 'Timestamp when the row was published to the broker',
    version         BIGINT       NOT NULL DEFAULT 0 COMMENT 'Optimistic-lock version; CAS-incremented on every state transition',
    created_time    DATETIME              COMMENT 'Created time',
    updated_time    DATETIME              COMMENT 'Updated time',
    created_id      BIGINT                COMMENT 'Created by user ID',
    created_by      VARCHAR(100)          COMMENT 'Created by username',
    updated_id      BIGINT                COMMENT 'Updated by user ID',
    updated_by      VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_status_next (status, next_attempt_at),
    INDEX idx_aggregate (aggregate_type, aggregate_id)
) COMMENT = 'Transactional outbox for message-starter Mail / SMS send paths';
