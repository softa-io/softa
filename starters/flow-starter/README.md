# Flow Starter

`flow-starter` is Softa's metadata-driven flow engine starter. It covers:

- design-time graph documents for a visual editor
- compile-time validation and transformation into runtime bundles
- published bundle revisions by `designId`
- runtime execution for process, validation, and compute flows
- persistent projections for instances, approval tasks, approval records, delegations, CC rules, and trigger events
- optional Pulsar-based trigger and async-task integration

This README documents the current backend contract implemented in this module.

## Current Model

### Design, Publish, Runtime

| Layer | Main types | Notes |
|---|---|---|
| Design time | `DesignFlowDefinition`, `FlowGraphDocument`, `FlowGraphNode`, `FlowGraphEdge` | Editor-facing graph DTOs |
| Publish time | `FlowCompiler`, `CompiledFlowDefinition`, `FlowPublishService` | Compile, validate, publish revisions, rollback |
| Runtime | `FlowRuntimeEngine`, `FlowExecutionState` | Execute published bundles, suspend/resume, persist projections |

### Flow Identity And Revisions

- `FlowDesign` is the draft working copy.
- `FlowBundle` is the compiled published snapshot.
- Published revisions are keyed by `designId`, not by `flowCode`.
- `FlowStartRequest` resolves bundles in this order:
  1. `bundleId` — pin to an exact published revision
  2. `designId` — start the current active revision
- To start a specific historical revision, look up its `bundleId` via the revisions listing endpoint and pass that as `bundleId`.
- `FlowBundle.designJson` keeps the design snapshot used at publish time so the editor can restore a historical canvas.

### Flow Scenarios

The module now uses a single `FlowScenario` enum instead of the old `FlowKind + FlowPurpose` split.

| Scenario | Meaning |
|---|---|
| `PROCESS` | Stateful runtime flow with persisted instances; covers both approval-style and automation-style flows |
| `VALIDATION` | Stateless synchronous validation flow — evaluated transiently: no instance row, no trace, no event log |
| `COMPUTE` | Stateless synchronous compute/onchange flow — evaluated transiently, same zero-footprint rules |

The scenario decides the execution strategy: `PROCESS` flows go through `FlowRuntimeEngine.start`
(persisted, resumable); `VALIDATION` / `COMPUTE` flows go through `evaluate` — the same traversal
with zero flow footprint. Business writes made by task nodes still join the caller's transaction,
so a failed evaluation rolls them back.

### Node Types

Current `FlowNodeType` values:

- Control: `Start`, `End`, `Timer`
- Routing: `InclusiveGateway`, `ParallelFork`, `ParallelJoin`
- Human: `Approval`, `HumanTask`*
- Task: `Script`, `CreateRecord`, `GetRecord`, `UpdateRecord`, `DeleteRecord`, `QueryRecords`, `ValidateData`, `Transform`, `CallService`, `CallWebhook`, `SendEmail`, `SendSms`, `SendInboxNotification`, `QueryAI`, `AsyncTask`, `GenerateFile`
- Subflow: `Subflow`
- Data: `ForEach`*, `ReturnValue`

\* `HumanTask` and `ForEach` are defined but **not yet runtime-supported**: they are omitted
from the node palette and rejected at compile time (`UNSUPPORTED_NODE_TYPE`), so a flow
containing them cannot publish.

Node-behavior invariants:

- **`Subflow` is synchronous-only**: the orchestrator fails the parent if a child
  enters a waiting state (`WAITING_APPROVAL` / `TIMER` / `ASYNC`), and recursive
  subflow invocation is rejected. Model human steps in the parent flow, not a child.
- **`ParallelFork` branches execute serially** (deterministic order), not
  wall-clock-parallel — the gateway models routing topology, not concurrency.

### Runtime Statuses

Defined `FlowExecutionStatus` values:

- `Pending`
- `Running`
- `Waiting` — parked on one or more waits (a pending approval and/or timer / async callback). The
  specific reasons live in the instance's pending-approval list and wait-token list, so a single
  status covers parallel branches suspended for different reasons at once.
- `Rejected`
- `Returned`
- `Withdrawn`
- `Cancelled`
- `Completed`
- `Failed`

## API Overview

Host applications embedding the engine in-process should integrate through the
`FlowClient` facade (`io.softa.starter.flow.api`) rather than looping back over
REST — actor / initiator / tenant identity is always resolved server-side from the
context, never passed as call parameters. See
[docs/integration-guide.md](docs/integration-guide.md).

### Design APIs (flow editor)

Base path: `/flow/designs` — the dedicated surface for the graphical editor
(full contract in [`docs/frontend-editor-api.md`](docs/frontend-editor-api.md)).
The generic model API at `/FlowDesign/**` remains available as the platform
data plane and is intentionally not intercepted.

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/designs` | Create a draft |
| `GET` | `/flow/designs?keyword=&scenario=&pageNumber=&pageSize=` | Paged draft list (canvas excluded) |
| `GET` | `/flow/designs/{id}` | Load one draft (canvas + optimistic-lock version) |
| `POST` | `/flow/designs/{id}/save` | Auto-save the canvas; stale versions are rejected |
| `POST` | `/flow/designs/{id}/delete` | Delete a draft if it has never been published |
| `POST` | `/flow/designs/{id}/duplicate` | Copy a draft under a fresh flow code |
| `POST` | `/flow/designs/validate` | Validate the posted document; diagnostics returned as a success payload |
| `POST` | `/flow/designs/{id}/publish` | Publish the saved draft as a new active revision |
| `POST` | `/flow/designs/{id}/restore?bundleId=` | Restore the draft canvas from a historical bundle snapshot |
| `GET` | `/flow/designs/{id}/status` | Publish status: revision badge + dirty flag |
| `GET` | `/flow/designs/{id}/availableVariables?nodeId=` | Variables visible to a node (expression autocomplete) |
| `POST` | `/flow/designs/{id}/debugRun` | Compile and run the current draft as a debug bundle (not a sandbox) |
| `GET` | `/flow/designs/{id}/debugRuns` | Debug-run history, newest first |

### Bundle APIs (published revisions)

Base path: `/flow/bundles`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/bundles?designId=` | Revision list of one design (or every design's active bundle without `designId`) |
| `GET` | `/flow/bundles/{bundleId}?include=design` | One revision's summary, optionally with the canvas-complete design snapshot |
| `POST` | `/flow/bundles/{bundleId}/activate` | Make this revision the design's effective one (rollback = roll-forward) |

### Node Descriptor APIs

Base path: `/flow/nodeDescriptors`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/nodeDescriptors` | List all node descriptors for the editor palette |
| `GET` | `/flow/nodeDescriptors?scenario=PROCESS` | Filter descriptors by `FlowScenario` |

### Runtime APIs

Base path: `/flow/runtime`

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/runtime/instances/start` | Start a flow instance |
| `POST` | `/flow/runtime/instances/debug` | Start and return the resolved bundle snapshot plus runtime state |
| `GET` | `/flow/runtime/instances/{instanceId}?includeTrace=` | Get one runtime instance (trace excluded by default) |
| `GET` | `/flow/runtime/instances/{instanceId}/overlay` | Per-node run state for canvas painting |
| `GET` | `/flow/runtime/instances/{instanceId}/trace?sinceSequence=` | Incremental trace rows for polling |
| `POST` | `/flow/runtime/instances/search` | Paged instance summaries (filters: flowCode/designId/status/initiator/model/row) |
| `POST` | `/flow/runtime/instances/approve` | Approve a pending approval |
| `POST` | `/flow/runtime/instances/reject` | Reject a pending approval |
| `POST` | `/flow/runtime/instances/transfer` | Transfer a pending approval task |
| `POST` | `/flow/runtime/instances/delegate` | Delegate a pending approval task |
| `POST` | `/flow/runtime/instances/add-sign-before` | Insert a prerequisite signer |
| `POST` | `/flow/runtime/instances/add-sign-after` | Insert a follow-up signer |
| `POST` | `/flow/runtime/instances/cc` | Send a CC notification |
| `POST` | `/flow/runtime/instances/cc/read` | Mark a CC as read |
| `POST` | `/flow/runtime/instances/return` | Return a pending approval |
| `POST` | `/flow/runtime/instances/resubmit` | Resubmit a returned instance |
| `POST` | `/flow/runtime/instances/withdraw` | Withdraw a flow instance |
| `POST` | `/flow/runtime/instances/urge` | Urge pending approvers |
| `POST` | `/flow/runtime/instances/comment` | Add a comment audit entry |
| `GET` | `/flow/runtime/instances/{instanceId}/nodes/{nodeId}/formPermissions` | Get field-level form permissions |
| `POST` | `/flow/runtime/trigger` | Fire a trigger event and start matching flows |
| `POST` | `/flow/runtime/onchange` | Run `COMPUTE` flows transiently and return variable diffs |
| `POST` | `/flow/runtime/validate` | Run `VALIDATION` flows transiently against candidate row data; returns each flow's declared outputs keyed by flow code |

Engine-internal resume callbacks live on `/internal/flow/runtime/instances/{resumeAsync|resumeTimer}`
(INTERNAL identity scope) — they are not part of the user-facing surface.

`actorId` / `initiatorId` / `tenantId` are **never** sent by clients: every action
request stamps them server-side from the login context (`@JsonProperty(READ_ONLY)`),
and all query endpoints resolve the current actor from the context.

## Approval Projection APIs

### Approval Tasks

Base path: `/flow/approvalTasks`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/approvalTasks/pending` | Pending tasks for the current actor (paged: returns `Page`) |
| `GET` | `/flow/approvalTasks/completed` | Completed tasks for the current actor (paged) |
| `GET` | `/flow/approvalTasks/cc` | CC tasks for the current actor (paged; `read=` filters unread/read) |
| `GET` | `/flow/approvalTasks/inbox` | Unified inbox view |
| `GET` | `/flow/approvalTasks/instance/{instanceId}` | All tasks for one runtime instance |

### Approval Records

Base path: `/flow/approvalRecords`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/approvalRecords/instance/{instanceId}` | Approval history by runtime instance |
| `GET` | `/flow/approvalRecords/history` | Actor-scoped approval history |
| `GET` | `/flow/approvalRecords/cc/sent` | Sender-side CC history |

### CC Config

Base path: `/flow/ccConfigs`

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/ccConfigs` | Create a CC rule |
| `GET` | `/flow/ccConfigs?flowCode=` | List CC rules by `flowCode` |
| `POST` | `/flow/ccConfigs/{id}/deactivate` | Deactivate a CC rule |

### Delegation

Base path: `/flow/delegations`

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/delegations` | Create a delegation rule |
| `GET` | `/flow/delegations/my?delegatorId=` | Delegations created by one delegator |
| `GET` | `/flow/delegations/to-me?delegateId=` | Active delegations assigned to one delegate |
| `POST` | `/flow/delegations/{id}/cancel` | Cancel a delegation rule |

### Event Logs

Base path: `/flow/events`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/events?flowCode=&sourceModel=&sourceRowId=&instanceId=&success=&pageNumber=&pageSize=` | Paged event log, newest first (filters combine with AND; list rows exclude the parameters payload) |

## Approval Model

### Approval And Reject Thresholds

- Approval modes: `ANY_ONE`, `UNANIMOUS`, `MIN_COUNT`, `PERCENTAGE`
- Reject modes: `ANY_ONE`, `UNANIMOUS`, `MIN_COUNT`, `PERCENTAGE`
- One actor can only cast one effective vote per cycle.
- Compile-time validators reject impossible threshold combinations.

### Approver Resolution

Approval nodes support:

- static approvers via `config.approvers`
- dynamic approvers via `config.approverSource`
- empty-approver strategies via `config.emptyApproverStrategy`

Supported dynamic source types in the current default resolution path:

- `VariableList`
- `Expression`
- `InitiatorManager`
- `Role`

Important integration note:

- `OrganizationService` is defined as an SPI (`MetadataOrganizationService` is the default when wired). The default resolver path also supports variable-based sources via `ApproverResolutionService`.
- If you need real org-tree lookup, provide a custom `OrganizationService` bean or populate resolution variables such as `initiatorManagerId` and `roleApprovers` before the flow reaches the approval node.

Approver dedup: the default policy is **`GLOBAL`** — a user who already approved an
earlier node is auto-passed on later nodes (recorded as `AUTO_APPROVE`). Residual
risk: a mid-flow form edit is still auto-endorsed by that earlier approval; set the
policy to `CONTIGUOUS` or `NONE` on flows where a re-look matters.

### Additional Approval Actions

- transfer
- delegate
- add-sign-before
- add-sign-after
- cc
- read-cc
- return
- resubmit
- withdraw
- urge
- comment

### Timeout Handling

Approval timeout strategies:

- `REMIND`
- `AUTO_APPROVE`
- `AUTO_REJECT`
- `ESCALATE`

`ApprovalTimeoutScheduler` handles reminders, auto actions, and escalation against persisted pending approvals.

## Task Execution Model

Executor-backed task nodes use `TaskNodeConfig`. The node **`FlowNodeType`**
selects the executor — there is no separate `executor` field in config.

```json
{
  "input": {
    "to": ["{{ applicantEmail }}"],
    "subject": "Order {{ orderId }} confirmed",
    "htmlBody": "<h1>Thank you for your order!</h1>"
  },
  "outputVariable": "taskResult"
}
```

- `input` supports `{{ expr }}` interpolation; resolution is owned by the executor
  (type-aware for data executors, recursive interpolation for free-shape payloads)
- `options` is executor-specific
- `outputVariable`, when present, stores the raw executor result under one variable name

Every task node type has a **typed input config DTO** registered in `TaskConfigTypes`:
the compiler parses `config.input` into the DTO at publish time and rejects missing
required keys (`MISSING_REQUIRED_INPUT`), so a misconfigured node fails at publish
instead of mid-execution. `DefaultTaskExecutorRegistry` asserts at boot that every
registered executor's node type has such an entry.

### Bundled Task Executors

| Node type | Executor bean | Notes |
|---|---|---|
| `CREATE_RECORD` | `CreateDataTaskExecutor` | Built in |
| `GET_RECORD` | `GetDataTaskExecutor` | Built in |
| `UPDATE_RECORD` | `UpdateDataTaskExecutor` | Built in |
| `DELETE_RECORD` | `DeleteDataTaskExecutor` | Built in |
| `QUERY_RECORDS` | `QueryRecordsTaskExecutor` | Built in; opt-out via `flow.task.builtin.query-records.enabled=false` to register a custom executor |
| `VALIDATE_DATA` | `ValidateDataTaskExecutor` | Built in |
| `TRANSFORM` | `ExtractTransformTaskExecutor` | Built in |
| `CALL_WEBHOOK` | `WebHookTaskExecutor` | Built in |
| `CALL_SERVICE` | `CallServiceTaskExecutor` | **Disabled by default**. Opt-in via `flow.task.builtin.call-service.enabled=true`; a non-empty `flow.task.call-service.allow-list` of permitted bean-name prefixes is then mandatory |
| `SEND_EMAIL` | `SendEmailTaskExecutor` | Registered only when `MessageService` is available |
| `SEND_SMS` | `SendSmsTaskExecutor` | Registered only when `MessageService` is available |
| `SEND_INBOX_NOTIFICATION` | `SendInboxNotificationTaskExecutor` | Registered only when `MessageService` is available |
| `QUERY_AI` | `QueryAiTaskExecutor` | Registered only when `AiRobotService` is available |
| `ASYNC_TASK` | `AsyncTaskExecutor` | Registered only when `FlowAsyncTaskProducer` is available |
| `GENERATE_FILE` | `GenerateFileTaskExecutor` | Registered only when `DocumentTemplateService` (file-starter) is available |

Current module notes:

- `CALL_SERVICE` ships a bundled executor but is **disabled by default**: it invokes an arbitrary Spring bean method by name, so it must be explicitly enabled and given a non-empty bean-name allow-list.

## Trigger And Messaging Integration

Supported trigger sources (`TriggerSource` sealed sub-types):

- `EntityChange`
- `Api`
- `Cron`
- `Subflow`
- `FieldChange` (COMPUTE scenario only)

Pulsar-connected components are conditionally registered:

| Property | Component | Purpose |
|---|---|---|
| `mq.topics.flow-event.topic` | `FlowEventProducer`, `FlowEventConsumer` | Async trigger fire and consume |
| `mq.topics.flow-async-task.topic` | `FlowAsyncTaskProducer`, `FlowAsyncTaskConsumer` | Async task dispatch and callback resume |
| `mq.topics.change-log.topic` | `ChangeLogFlowConsumer` | Entity change driven flow triggers |
| `mq.topics.cron-task.topic` | `CronTaskFlowConsumer` | Cron-driven flow triggers |
| `mq.topics.flow-timer.topic` | `FlowTimerConsumer` | Timer wake-up consume side |

Important timer note:

- The module includes the timer resume consumer.
- A scheduler or producer that publishes `FlowTimerMessage` still has to be provided by the host application or another integration module.

## Persistence Model

| Entity | Description |
|---|---|
| `FlowDesign` | Draft working copy |
| `FlowBundle` | Published compiled bundle |
| `FlowInstance` | Persisted runtime instance state (trace and approval history are NOT embedded — see the two dedicated tables) |
| `FlowExecutionTrace` | Append-only execution trace rows (the in-memory state keeps only the current attempt's delta) |
| `FlowApprovalTask` | Pending/completed/CC task projections |
| `FlowApprovalRecord` | Approval action audit ledger — the authoritative approval history, flushed by the instance store in the same transaction as the instance row |
| `FlowCcConfig` | Automatic CC rules |
| `FlowDelegation` | Delegation rules |
| `FlowEvent` | Trigger event logs |
| `FlowDebugHistory` | Debug snapshots |
| `FlowFormDefinition` | Form definitions bound to flow nodes |
| `FlowParallelBranch` | Parallel branch tracking |

Primary storage adapters in this module:

- `OrmFlowBundleRegistry`
- `OrmFlowInstanceStore`

## Node Descriptor Contract

The editor palette is built from `FlowNodeDescriptor` records exposed through `/flow/nodeDescriptors`.

- structural descriptors come from `BuiltinNodeDescriptorProvider`
- executor-backed task descriptors come from `TaskExecutorDescriptorProvider`
- descriptors include label, icon, sort order, config schema, default config, and allowed scenarios

`TaskExecutorDescriptorProvider` only exposes task nodes for executors that are actually registered as Spring beans, so conditional executors such as `QUERY_AI` and `ASYNC_TASK` only appear when their dependencies are present.

## Frontend Graph Compatibility

Design graph DTOs remain aligned with xyflow/react style structures:

| xyflow/react | flow-starter |
|---|---|
| `Node.id` | `FlowGraphNode.id` |
| `Node.type` | `FlowGraphNode.type` |
| `Node.position` | `FlowGraphNode.position` |
| `Node.width` / `height` | `FlowGraphNode.width` / `height` |
| `Node.data` | `FlowGraphNode.data` |
| `Edge.id` | `FlowGraphEdge.id` |
| `Edge.source` / `target` | `FlowGraphEdge.source` / `target` |
| `Edge.sourceHandle` / `targetHandle` | `FlowGraphEdge.sourceHandle` / `targetHandle` |
| `Edge.type` | `FlowGraphEdge.type` |
| `Edge.label` | `FlowGraphEdge.label` |
| `Edge.data` | `FlowGraphEdge.data` |
| `Viewport {x, y, zoom}` | `FlowGraphViewport {x, y, zoom}` |

## Host Application Integration Checklist

Before enabling `flow-starter` in a real app, verify:

- add `flow-starter` to the application classpath
- create and expose `FlowDesign` drafts through the generic model APIs
- provide a real `ApprovalNotificationService` if you need email, SMS, IM, or in-app notifications
- provide a custom `OrganizationService` or populate approver resolution variables if you need manager/role/department based approvers
- enable Pulsar topics if using triggers or async tasks
- provide timer scheduling/publishing if using `TIMER` nodes
- enable `CALL_SERVICE` explicitly (plus its bean-name allow-list) if flows need to invoke Spring beans; all other task node types ship working executors, conditionally registered on their capability beans
