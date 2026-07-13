-- ============================================================
-- flow-starter DDL
-- All tables use CREATE TABLE IF NOT EXISTS.
-- Standard audit columns: created_time, updated_time, created_id, updated_id, created_by, updated_by
--
-- Multi-tenancy: every runtime / projection table carries
-- `tenant_id` and indexes lead with it.
--
-- Trace storage: execution trace is stored in a dedicated
-- append-only table `flow_execution_trace` instead of a LONGTEXT column on
-- `flow_instance`. Approval action audit history likewise lives in the
-- dedicated `flow_approval_record` table (flushed by the instance store),
-- not in a LONGTEXT column.
--
-- Inbox query indexes: composite indexes lead with
-- `(tenant_id, actor_id, status, ...)` to support SQL-side filtering and
-- range scans for inbox / history endpoints.
-- ============================================================

-- ------------------------------------------------------------
-- 1. flow_design — Working copy of a flow design (draft)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_design
(
    id                BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id         BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    flow_name         VARCHAR(100) NOT NULL COMMENT 'Flow display name',
    flow_code         VARCHAR(100) NOT NULL COMMENT 'Flow code (business identifier)',
    scenario          VARCHAR(50)           COMMENT 'Flow scenario',
    version           INT          NOT NULL DEFAULT 1 COMMENT 'Optimistic-lock version',
    design_json       LONGTEXT              COMMENT 'Full design definition (JSON)',
    published_revision INT                   COMMENT 'Revision of the most recent successful publish',
    published_checksum VARCHAR(64)           COMMENT 'SHA-256 of the most recently published design JSON',
    created_time      DATETIME              COMMENT 'Created time',
    updated_time      DATETIME              COMMENT 'Updated time',
    created_id        BIGINT                COMMENT 'Created by user ID',
    created_by        VARCHAR(100)          COMMENT 'Created by username',
    updated_id        BIGINT                COMMENT 'Updated by user ID',
    updated_by        VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_tenant_flow_code (tenant_id, flow_code),
    INDEX idx_tenant_scenario (tenant_id, scenario)
) COMMENT = 'Working copy of a flow design (draft)';

-- ------------------------------------------------------------
-- 2. flow_bundle — Compiled and published flow revision
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_bundle
(
    id                 BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id          BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    version            INT          NOT NULL DEFAULT 1 COMMENT 'Optimistic-lock version (concurrent publish/activate)',
    flow_code          VARCHAR(100) NOT NULL COMMENT 'Flow code',
    flow_name          VARCHAR(100)          COMMENT 'Flow name',
    revision           INT          NOT NULL COMMENT 'Published revision number',
    scenario           VARCHAR(50)           COMMENT 'Execution scenario',
    sync               TINYINT(1)   DEFAULT 0 COMMENT 'Whether the flow executes synchronously',
    rollback_on_fail   TINYINT(1)   DEFAULT 1 COMMENT 'Whether to roll back on failure',
    design_id          BIGINT                COMMENT 'FK to flow_design.id',
    compiled_json      LONGTEXT              COMMENT 'Compiled flow definition (JSON)',
    design_json        LONGTEXT              COMMENT 'Design flow definition at publish time (JSON)',
    compiled_at        DATETIME              COMMENT 'Compile timestamp',
    published_at       DATETIME              COMMENT 'Published timestamp',
    change_description VARCHAR(500)          COMMENT 'Change description for this revision',
    active             TINYINT(1)   DEFAULT 1 COMMENT 'Whether this is the currently effective revision (one per design)',
    debug              TINYINT(1)   DEFAULT 0 COMMENT 'Whether this is a debug-run bundle',
    created_time       DATETIME              COMMENT 'Created time',
    updated_time       DATETIME              COMMENT 'Updated time',
    created_id         BIGINT                COMMENT 'Created by user ID',
    created_by         VARCHAR(100)          COMMENT 'Created by username',
    updated_id         BIGINT                COMMENT 'Updated by user ID',
    updated_by         VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_tenant_design_revision (tenant_id, design_id, revision),
    INDEX idx_tenant_design (tenant_id, design_id),
    INDEX idx_tenant_flow_revision (tenant_id, flow_code, revision, active)
) COMMENT = 'Compiled and published flow revision bundle';

-- ------------------------------------------------------------
-- 3. flow_instance — Runtime execution instance
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_instance
(
    id                      BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id               BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    version                 INT          NOT NULL DEFAULT 1 COMMENT 'Optimistic-lock version',
    instance_id             VARCHAR(64)  NOT NULL COMMENT 'Runtime instance id (UUID)',
    bundle_id               BIGINT                COMMENT 'Exact flow bundle id this instance was created from',
    design_id               BIGINT                COMMENT 'Source flow design id',
    flow_code               VARCHAR(100) NOT NULL COMMENT 'Flow code',
    flow_revision           INT                   COMMENT 'Published flow revision',
    title                   VARCHAR(200)          COMMENT 'Instance title',
    model_name              VARCHAR(100)          COMMENT 'Related model name',
    row_id                  VARCHAR(64)           COMMENT 'Related row data ID',
    initiator_id            VARCHAR(64)           COMMENT 'Flow initiator id',
    status                  VARCHAR(30)  NOT NULL COMMENT 'Execution status',
    resubmission_count      INT          DEFAULT 0 COMMENT 'Resubmission count after return',
    error_message           TEXT                  COMMENT 'Error message when execution fails',
    failed_node_id          VARCHAR(100)          COMMENT 'Node where execution failed',
    input_payload           LONGTEXT              COMMENT 'Immutable trigger payload (JSON)',
    variables               LONGTEXT              COMMENT 'Execution variables (JSON)',
    wait_tokens             LONGTEXT              COMMENT 'Active timer/async waits (JSON array); pending approvals tracked separately',
    next_fire_at            DATETIME              COMMENT 'Earliest timer-wait due time (denormalized from wait_tokens for the sweep index)',
    completed_node_ids      LONGTEXT              COMMENT 'Completed node ids (JSON array)',
    pending_approvals       LONGTEXT              COMMENT 'Pending approvals (JSON array)',
    returned_approval       LONGTEXT              COMMENT 'Returned approval context (JSON)',
    join_arrival_counts     LONGTEXT              COMMENT 'Parallel join arrival counts (JSON map)',
    return_data             LONGTEXT              COMMENT 'Return data from ReturnData nodes (JSON)',
    created_time            DATETIME              COMMENT 'Created time',
    updated_time            DATETIME              COMMENT 'Updated time',
    created_id              BIGINT                COMMENT 'Created by user ID',
    created_by              VARCHAR(100)          COMMENT 'Created by username',
    updated_id              BIGINT                COMMENT 'Updated by user ID',
    updated_by              VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_instance_id (instance_id),
    INDEX idx_tenant_flow_status (tenant_id, flow_code, status),
    INDEX idx_tenant_status_fire (tenant_id, status, next_fire_at),
    INDEX idx_tenant_initiator_status (tenant_id, initiator_id, status),
    INDEX idx_tenant_model_row (tenant_id, model_name, row_id),
    INDEX idx_tenant_design (tenant_id, design_id)
) COMMENT = 'Runtime flow execution instance';

-- ------------------------------------------------------------
-- 4. flow_execution_trace — Append-only execution trace (split from flow_instance.trace)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_execution_trace
(
    id              BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id       BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    instance_id     VARCHAR(64)  NOT NULL COMMENT 'Runtime instance id',
    sequence        INT          NOT NULL COMMENT 'Monotonic position within the instance trace',
    flow_code       VARCHAR(100)          COMMENT 'Flow code at the time of the event',
    node_id         VARCHAR(100)          COMMENT 'Node id when the event is node-scoped',
    flow_node_type  VARCHAR(50)           COMMENT 'Node type when applicable',
    event_type      VARCHAR(50)  NOT NULL COMMENT 'Trace event type',
    event_time      DATETIME              COMMENT 'Event timestamp',
    message         TEXT                  COMMENT 'Free-form message',
    created_time    DATETIME              COMMENT 'Created time',
    updated_time    DATETIME              COMMENT 'Updated time',
    created_id      BIGINT                COMMENT 'Created by user ID',
    created_by      VARCHAR(100)          COMMENT 'Created by username',
    updated_id      BIGINT                COMMENT 'Updated by user ID',
    updated_by      VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_instance_sequence (instance_id, sequence),
    INDEX idx_tenant_instance (tenant_id, instance_id)
) COMMENT = 'Execution trace entries split out from flow_instance';

-- ------------------------------------------------------------
-- 5. flow_approval_task — Approval task projection
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_approval_task
(
    id                      BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id               BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    instance_id             VARCHAR(64)  NOT NULL COMMENT 'Runtime instance id',
    flow_code               VARCHAR(100)          COMMENT 'Flow code',
    flow_revision           INT                   COMMENT 'Published flow revision',
    node_id                 VARCHAR(100) NOT NULL COMMENT 'Approval node id',
    node_label              VARCHAR(200)          COMMENT 'Approval node label',
    cycle_number            INT          DEFAULT 1 COMMENT 'Cycle number for repeated visits',
    actor_id                VARCHAR(64)  NOT NULL COMMENT 'Assigned actor id',
    status                  VARCHAR(30)  NOT NULL COMMENT 'Task status',
    task_type               VARCHAR(30)           COMMENT 'Task type (APPROVAL, CC)',
    action                  VARCHAR(30)           COMMENT 'Latest action that changed this task',
    comment                 TEXT                  COMMENT 'Latest action comment',
    dynamic_approvers       TINYINT(1)   DEFAULT 0 COMMENT 'Whether approvers were resolved dynamically',
    approval_mode           VARCHAR(30)           COMMENT 'Approval mode snapshot',
    required_approval_count INT                   COMMENT 'Required approval count',
    total_approver_count    INT                   COMMENT 'Total approver count',
    reject_mode             VARCHAR(30)           COMMENT 'Reject mode snapshot',
    required_reject_count   INT                   COMMENT 'Required reject count',
    candidate_actors        TEXT                  COMMENT 'Candidate actor ids (JSON array)',
    approved_actors         TEXT                  COMMENT 'Actors who already approved (JSON array)',
    rejected_actors         TEXT                  COMMENT 'Actors who already rejected (JSON array)',
    start_time              DATETIME              COMMENT 'Task opened time',
    end_time                DATETIME              COMMENT 'Task closed time',
    due_time                DATETIME              COMMENT 'Task due time for timeout handling',
    remind_count            INT          DEFAULT 0 COMMENT 'Remind count for overdue notifications',
    urgency                 VARCHAR(30)           COMMENT 'Urgency level',
    batch_id                BIGINT                COMMENT 'Batch ID for batch approval operations',
    form_snapshot           LONGTEXT              COMMENT 'Form data snapshot (JSON)',
    closed_by_actor_id      VARCHAR(64)           COMMENT 'Actor who closed this task',
    blocked                 TINYINT(1)   DEFAULT 0 COMMENT 'Blocked by add-sign-before prerequisite',
    blocked_by_actor_id     VARCHAR(64)           COMMENT 'Actor who must act before this blocked task',
    created_time            DATETIME              COMMENT 'Created time',
    updated_time            DATETIME              COMMENT 'Updated time',
    created_id              BIGINT                COMMENT 'Created by user ID',
    created_by              VARCHAR(100)          COMMENT 'Created by username',
    updated_id              BIGINT                COMMENT 'Updated by user ID',
    updated_by              VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_tenant_instance_node (tenant_id, instance_id, node_id),
    INDEX idx_tenant_actor_status_start (tenant_id, actor_id, status, start_time),
    INDEX idx_tenant_actor_status_end (tenant_id, actor_id, status, end_time)
) COMMENT = 'Approval task projection for flow instances';

-- ------------------------------------------------------------
-- 6. flow_approval_record — Approval audit record projection
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_approval_record
(
    id                BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id         BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    instance_id       VARCHAR(64)  NOT NULL COMMENT 'Runtime instance id',
    flow_code         VARCHAR(100)          COMMENT 'Flow code',
    flow_revision     INT                   COMMENT 'Published flow revision',
    node_id           VARCHAR(100)          COMMENT 'Primary node id',
    node_label        VARCHAR(200)          COMMENT 'Primary node label',
    cycle_number      INT                   COMMENT 'Cycle number',
    task_id           BIGINT                COMMENT 'Task id when attached to one task row',
    sequence          INT          NOT NULL COMMENT 'Monotonic runtime sequence within one instance',
    action            VARCHAR(30)           COMMENT 'Action type',
    actor_id          VARCHAR(64)           COMMENT 'Operator actor id',
    target_actor_id   VARCHAR(64)           COMMENT 'Target actor id',
    add_sign_position VARCHAR(30)           COMMENT 'Add-sign position',
    target_node_id    VARCHAR(100)          COMMENT 'Target node id',
    target_node_label VARCHAR(200)          COMMENT 'Target node label',
    comment           TEXT                  COMMENT 'Comment',
    status_before     VARCHAR(30)           COMMENT 'Status before action',
    status_after      VARCHAR(30)           COMMENT 'Status after action',
    approved_actors   TEXT                  COMMENT 'Actors who had already approved (JSON array)',
    rejected_actors   TEXT                  COMMENT 'Actors who had already rejected (JSON array)',
    variable_keys     TEXT                  COMMENT 'Updated variable keys (JSON array)',
    event_time        DATETIME              COMMENT 'Recorded time',
    created_time      DATETIME              COMMENT 'Created time',
    updated_time      DATETIME              COMMENT 'Updated time',
    created_id        BIGINT                COMMENT 'Created by user ID',
    created_by        VARCHAR(100)          COMMENT 'Created by username',
    updated_id        BIGINT                COMMENT 'Updated by user ID',
    updated_by        VARCHAR(100)          COMMENT 'Updated by username',
    UNIQUE INDEX uk_instance_sequence (instance_id, sequence),
    INDEX idx_tenant_instance (tenant_id, instance_id),
    INDEX idx_tenant_actor_event (tenant_id, actor_id, event_time),
    INDEX idx_tenant_target_event (tenant_id, target_actor_id, event_time)
) COMMENT = 'Approval audit record projection for flow instances';

-- ------------------------------------------------------------
-- 7. flow_delegation — Delegation rules for approval tasks
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_delegation
(
    id                   BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id            BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    delegator_id         VARCHAR(64)  NOT NULL COMMENT 'Delegator actor id',
    delegate_id          VARCHAR(64)  NOT NULL COMMENT 'Delegate actor id',
    reason               VARCHAR(500)          COMMENT 'Reason',
    scope                VARCHAR(30)           COMMENT 'Delegation scope: All, FlowCode, Node',
    flow_code            VARCHAR(100)          COMMENT 'Flow code when scope is flow-specific',
    node_id              VARCHAR(100)          COMMENT 'Node id when scope is node-specific',
    start_time           DATETIME              COMMENT 'Delegation start time',
    end_time             DATETIME              COMMENT 'Delegation end time',
    active               TINYINT(1)   DEFAULT 1 COMMENT 'Whether the rule is active',
    auto_expire          TINYINT(1)   DEFAULT 1 COMMENT 'Auto expire at end time',
    delegated_task_count INT          DEFAULT 0 COMMENT 'Delegated task count',
    last_delegation_time DATETIME              COMMENT 'Last delegated time',
    created_time         DATETIME              COMMENT 'Created time',
    updated_time         DATETIME              COMMENT 'Updated time',
    created_id           BIGINT                COMMENT 'Created by user ID',
    created_by           VARCHAR(100)          COMMENT 'Created by username',
    updated_id           BIGINT                COMMENT 'Updated by user ID',
    updated_by           VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_tenant_delegator (tenant_id, delegator_id),
    INDEX idx_tenant_delegate_active (tenant_id, delegate_id, active)
) COMMENT = 'Delegation rules for flow approval tasks';

-- ------------------------------------------------------------
-- 8. flow_cc_config — CC (carbon copy) configuration rules
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_cc_config
(
    id                BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id         BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    flow_code         VARCHAR(100) NOT NULL COMMENT 'Flow code',
    node_id           VARCHAR(100)          COMMENT 'Node id (null for flow-level CC)',
    cc_timing         VARCHAR(30)           COMMENT 'CC timing: OnSubmit, OnApprove, OnReject, OnComplete',
    cc_name           VARCHAR(100)          COMMENT 'Human-readable CC rule name',
    recipient_type    VARCHAR(30)           COMMENT 'Recipient type: USER, ROLE, DEPT, INITIATOR, EXPRESSION',
    recipient_config  TEXT                  COMMENT 'Recipient configuration (JSON)',
    cc_condition      VARCHAR(1000)         COMMENT 'Optional condition expression',
    create_read_task  TINYINT(1)   DEFAULT 0 COMMENT 'Whether to create CC read tasks',
    send_notification TINYINT(1)   DEFAULT 1 COMMENT 'Whether to send notification',
    message_template  VARCHAR(500)          COMMENT 'Optional message template',
    active            TINYINT(1)   DEFAULT 1 COMMENT 'Whether this CC rule is active',
    created_time      DATETIME              COMMENT 'Created time',
    updated_time      DATETIME              COMMENT 'Updated time',
    created_id        BIGINT                COMMENT 'Created by user ID',
    created_by        VARCHAR(100)          COMMENT 'Created by username',
    updated_id        BIGINT                COMMENT 'Updated by user ID',
    updated_by        VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_tenant_flow_active (tenant_id, flow_code, active)
) COMMENT = 'CC (carbon copy) configuration rules for flows';

-- ------------------------------------------------------------
-- 9. flow_event — Trigger event log
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_event
(
    id            BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id     BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    trigger_type  VARCHAR(50)           COMMENT 'Trigger type discriminator (EntityChange, Api, Cron)',
    source_model  VARCHAR(100)          COMMENT 'Source model when trigger is entity-related',
    source_row_id VARCHAR(64)           COMMENT 'Source row id when trigger is entity-related',
    actor_id      VARCHAR(64)           COMMENT 'Actor who triggered the event',
    flow_code     VARCHAR(100)          COMMENT 'Flow code of the matched flow',
    flow_revision INT                   COMMENT 'Flow revision that was started',
    instance_id   VARCHAR(64)           COMMENT 'Runtime instance id of the started flow',
    success       TINYINT(1)            COMMENT 'Whether the flow was started successfully',
    error_message TEXT                  COMMENT 'Error message when the flow failed to start',
    fire_method   VARCHAR(50)           COMMENT 'Trigger fire method',
    event_time    DATETIME              COMMENT 'Event timestamp',
    parameters    LONGTEXT              COMMENT 'Trigger parameters (JSON)',
    created_time  DATETIME              COMMENT 'Created time',
    updated_time  DATETIME              COMMENT 'Updated time',
    created_id    BIGINT                COMMENT 'Created by user ID',
    created_by    VARCHAR(100)          COMMENT 'Created by username',
    updated_id    BIGINT                COMMENT 'Updated by user ID',
    updated_by    VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_tenant_flow (tenant_id, flow_code),
    INDEX idx_tenant_instance (tenant_id, instance_id),
    INDEX idx_tenant_source (tenant_id, source_model, source_row_id)
) COMMENT = 'Trigger event log for flow executions';

-- ------------------------------------------------------------
-- 10. flow_debug_history — Debug execution history
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_debug_history
(
    id              BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id       BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    flow_code       VARCHAR(100)          COMMENT 'Flow code',
    flow_revision   INT                   COMMENT 'Flow revision',
    instance_id     VARCHAR(64)           COMMENT 'Runtime instance id',
    status          VARCHAR(30)           COMMENT 'Final execution status',
    initiator_id    VARCHAR(64)           COMMENT 'Flow initiator id',
    parent_instance_id VARCHAR(64)        COMMENT 'Parent instance id (for sub-flow)',
    start_time      DATETIME              COMMENT 'Start time',
    end_time        DATETIME              COMMENT 'End time',
    duration_ms     BIGINT                COMMENT 'Duration in milliseconds',
    event_message   LONGTEXT              COMMENT 'Trigger event message (JSON)',
    node_trace      LONGTEXT              COMMENT 'Full node execution trace (JSON)',
    final_variables LONGTEXT              COMMENT 'Final variables snapshot (JSON)',
    error_message   TEXT                  COMMENT 'Error message if execution failed',
    created_time    DATETIME              COMMENT 'Created time',
    updated_time    DATETIME              COMMENT 'Updated time',
    created_id      BIGINT                COMMENT 'Created by user ID',
    created_by      VARCHAR(100)          COMMENT 'Created by username',
    updated_id      BIGINT                COMMENT 'Updated by user ID',
    updated_by      VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_tenant_flow (tenant_id, flow_code),
    INDEX idx_tenant_instance (tenant_id, instance_id)
) COMMENT = 'Debug execution history for flow instances';

-- ------------------------------------------------------------
-- 11. flow_parallel_branch — Parallel branch execution records
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flow_parallel_branch
(
    id             BIGINT       NOT NULL PRIMARY KEY COMMENT 'ID',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT 'Tenant ID',
    instance_id    VARCHAR(64)  NOT NULL COMMENT 'Runtime instance id',
    fork_node_id   VARCHAR(100) NOT NULL COMMENT 'Fork node id (parallel gateway)',
    branch_node_id VARCHAR(100) NOT NULL COMMENT 'Branch target node id',
    branch_name    VARCHAR(200)          COMMENT 'Branch name or label',
    status         VARCHAR(30)           COMMENT 'Branch status',
    start_time     DATETIME              COMMENT 'Branch start time',
    end_time       DATETIME              COMMENT 'Branch end time',
    duration_ms    BIGINT                COMMENT 'Duration in milliseconds',
    error_message  TEXT                  COMMENT 'Error message if the branch failed',
    result         LONGTEXT              COMMENT 'Branch result (JSON)',
    created_time   DATETIME              COMMENT 'Created time',
    updated_time   DATETIME              COMMENT 'Updated time',
    created_id     BIGINT                COMMENT 'Created by user ID',
    created_by     VARCHAR(100)          COMMENT 'Created by username',
    updated_id     BIGINT                COMMENT 'Updated by user ID',
    updated_by     VARCHAR(100)          COMMENT 'Updated by username',
    INDEX idx_tenant_instance_fork (tenant_id, instance_id, fork_node_id)
) COMMENT = 'Parallel branch execution records for flow instances';
