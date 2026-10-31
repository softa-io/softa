# Flow Starter

`flow-starter` is Softa's metadata-driven flow engine starter. It covers:

- design-time graph documents for a visual editor
- compile-time validation and transformation into runtime bundles
- published bundle revisions by `flowId`
- runtime execution for approval, automation, validation, and compute flows
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

- `FlowDesign` is the draft working copy. Its `id` (a distributed string id) is the flow's identity everywhere: bundles, instances, tasks, approval records, events and delegations reference it as `flowId`, and lists show the flow's name through that reference.
- `flowName` is unique within a tenant; there is no separate flow code.
- `FlowBundle` is the compiled published snapshot; published revisions are keyed by `flowId`.
- `FlowStartRequest` resolves bundles in this order:
  1. `bundleId` — pin to an exact published revision
  2. `flowId` — start the current active revision
- To start a specific historical revision, look up its `bundleId` via the revisions listing endpoint and pass that as `bundleId`.
- `FlowBundle.designJson` keeps the design snapshot used at publish time so the editor can restore a historical canvas.

### Enabling And Disabling A Flow

A flow has an on/off switch, `FlowDesign.enabled` (on when created), apart from which revision is effective:

- **Off**, nothing starts it: no submission picks it, no data change, field change or schedule fires it, an API start
  (`POST /flow/instances/start`, or `FlowStartRequest.flowId`) and a parent flow's Subflow node are refused — "it was
  never published, or it is disabled".
- The effective revision stays as it is, so switching it back on runs the same one; activating another revision and
  publishing leave the switch as it is — a flow can be prepared and published while off, then switched on.
- **A flow is switched off only once nothing of it is left running**: refused while any of its instances has not
  finished — pending, running or waiting, a record still in approval among them (`FlowInstanceService.countUnfinished`)
  — and, for an automation flow, while a flow that may still run it as a subflow calls it: an enabled flow's effective
  revision, or the revision an unfinished instance of another flow runs on (an instance keeps the revision it started
  on). The refusal says how many instances are left, or names the callers. A failed instance has ended: it does not
  hold the flow on.
- `POST /flow/designs/{id}/disable` / `enable` switch it; `GET /flow/designs/{id}/status` reports it.
- **A published flow is never deleted**: its revisions, instances and approval history refer to it. The refusal is
  a write validator (`PublishedFlowDeleteGuard`), so it holds for the generic `/FlowDesign` API as well as for
  `POST /flow/designs/{id}/delete`; a flow that was never published is deleted freely.

### Flow Scenarios

A single `FlowScenario` enum declares what a flow is. It is chosen when the flow is created and fixed
from then on (`FlowDesign.scenario` is readonly on update; the design document follows the column).

| Scenario | Meaning | Triggers it may declare |
|---|---|---|
| `APPROVAL` | A record's approval: started by submitting the record, approval steps, the outcome written back to the record. Persisted instances, kept as the record's approval history | `Submit` |
| `AUTOMATION` | Started by a change, a cron, an explicit start or a parent flow; no approval steps. Persisted runs, purged after their retention period | `EntityChange`, `Cron`, `Api`, `Subflow` |
| `VALIDATION` | Stateless synchronous validation flow — evaluated transiently: no instance row, no trace, no event log | `EntityChange`, `Cron` |
| `COMPUTE` | Stateless synchronous compute/onchange flow — evaluated transiently, same zero-footprint rules | `FieldChange`, `EntityChange`, `Cron` |

The scenario decides the execution strategy: `APPROVAL` / `AUTOMATION` flows go through
`FlowRuntimeEngine.start` (persisted, resumable); `VALIDATION` / `COMPUTE` flows go through `evaluate` —
the same traversal with zero flow footprint. Business writes made by task nodes still join the caller's
transaction, so a failed evaluation rolls them back.

Every flow declares a trigger, and **the trigger is the flow's start contract** — one way in each:

| Trigger | How the flow starts |
|---|---|
| `Submit` | Only by submitting a record (`FlowClient.submitRecord`) — see [Approving Business Records](#approving-business-records) |
| `EntityChange` / `FieldChange` | Only by their events |
| `Cron` | Only on its own schedule — see [Cron-triggered flows](#cron-triggered-flows) |
| `Api` | Only explicitly, by id: `POST /flow/instances/start`, `FlowClient.start` / `publishAndStart` |
| `Subflow` | Only from a parent flow's Subflow node |

An explicit start and a Subflow call both run an Automation flow, so `Api` and `Subflow` belong to
Automation alone. Debug runs of a draft ignore the trigger.

### A Flow's Start: Its Trigger And The Model It Names

A flow has one trigger and one Start node — one start contract, configured on the Start node and stored as the
document's `trigger` beside its graph. Several ways in for the same logic are several thin flows calling one
Subflow. A trigger that starts the flow on a record names **the record's model** — an Approval flow's document, the
model a change trigger watches; one that starts on no record (a schedule, an API call, a parent flow) names none.

- The model is the trigger's: `SubmitTrigger.modelName`, `EntityChangeTrigger.modelName`,
  `FieldChangeTrigger.modelName` (`TriggerSource.modelName()`; `onRecord()` says which kinds must name one). A flow
  touching several models — an automation that reads one and writes others — has no model of its own: its nodes name
  theirs. `CompiledFlowDefinition.getModelName()` reads it from the trigger, and the variables the start offers, the
  edges' rule conditions and an approval's field permissions all follow it. Publishing refuses a record trigger
  without a model (`MISSING_MODEL`).
- **Changing the trigger may change the model** — another trigger kind of the scenario, another model to watch: the
  next publish checks the graph against it (rule conditions, watched fields), and the running instances keep their
  own revision.
- `FlowDesign.modelName` is the trigger's model kept as a column, for lookups — the flow list, a document's approval
  flow: written on every save and publish, empty for a trigger that starts on no record. It is not edited apart.
- **An Approval flow's document is fixed.** It is chosen when the flow is created (`POST /flow/designs` with
  `modelName`) and never changes: every save and publish puts the trigger's `modelName` back from the column,
  whatever the draft says, and keeps the condition the draft gives it. A design that names its document only in its
  trigger — a seeded one — has the column taken from it the first time it is published.
- **A document may have several approval flows**, told apart by their `SubmitTrigger` condition — one of the two kinds
  an edge takes, never both: a rule over the record (`condition`, checked at publish against the fields the document's
  approvals route on) or an AviatorScript `conditionExpression`. A submission goes to the document's enabled approval
  flow whose condition holds for the record — read over what the approval would start with (the record's fields and
  its handler's variables); no condition holds for every record — and exactly one must hold: none, or several, refuse
  the submission naming why. A leave and its cancellation are two flows of `LeaveRequest` this way, and so is a flow per
  country or company. Conditions that overlap show when a record is submitted or previewed; the very same condition
  twice — neither flow having one, or the same rule or expression — is refused when such a flow is published while
  enabled, enabled, or rolled back (`SubmitConditions`). `POST /flow/designs` refuses an approval flow for a document
  without an approval handler, and publishing refuses one on a model the application cannot approve
  (`MODEL_NOT_APPROVABLE`, from `ApprovableModelValidator`, a `FlowValidator` bean, so the designer's validation
  reports it too). `GET /flow/approvalModels` lists the approvable documents, each with the names of its approval flows
  — what a new approval flow picks from.
- **A flow is copied as an unpublished draft** (`POST /flow/designs/{id}/duplicate`) — an approval flow too, for
  another country or company: the copy approves the same document with the same condition, which it changes before
  it is published beside the source.
- **A data change starts the flow when its condition holds.** An `EntityChangeTrigger` may carry a condition of one
  of the two kinds an edge takes, never both (`CONFLICTING_TRIGGER_CONDITIONS`): a rule over the record —
  `condition`, the platform `Filters` JSON, judged like an edge's rule by the types of the trigger model's fields and
  checked at publish the same way — or an AviatorScript `conditionExpression`, compiled at publish; either failing is
  `INVALID_TRIGGER_CONDITION`. Both read the variables the flow would start with (`FlowTriggerEvent.startVariables`):
  the change's data — the whole row on a create or a delete, **the changed fields' new values on an update** — with
  `_triggerType`, `_sourceModel` and `_sourceRowId`. A condition that cannot be evaluated starts nothing and is never
  a quiet "no": the fire is logged as failed in the event log, a sync `rollbackOnFail` flow fails the write that
  fired it, and a Validation flow fails the validation (`DefaultFlowTriggerRegistry.findMatches`). A
  `FieldChangeTrigger` watches fields its model has (`UNKNOWN_TRIGGER_FIELD`).

What a trigger "matches" depends on the scenario: a submission goes to the one approval flow of its document whose
condition holds for the record; a data change, a field change or a schedule starts every enabled Automation,
Validation or Compute flow that watches it — independent Automation runs, Validation results gathered by flow, Compute outputs merged. An output has one
author among a model's enabled Compute flows — two declaring it would overwrite each other — so publishing,
enabling or rolling back a Compute flow is refused while another enabled Compute flow of its model declares one of
its outputs (`ComputeOutputs`). An API call or a parent names the flow it starts.

Settings belong to one scenario too: `withdrawPolicy`, `urgeEnabled`, `urgeIntervalMinutes`, `approverDedup`,
`selfApproval`, `emailNotification` and `cc` to Approval flows; `sync` / `rollbackOnFail` to Automation flows, and a sync flow — which runs inside the writer's
transaction — may not contain a Timer, AsyncTask or Approval node. The compiler rejects a flow that
breaks any of these rules.

| Approval setting | Values | Default |
|---|---|---|
| `withdrawPolicy` | Until when the initiator may withdraw: `Never`, `BeforeFirstAction` (until an approver first approves, rejects, returns, transfers, delegates or adds a signer — an automatic approval does not count), `UntilCompleted` | `Never` |
| `urgeEnabled` | Whether the initiator may urge the approvers the approval waits on; an administrator may either way | on |
| `urgeIntervalMinutes` | How often each waiting step may be urged, by the initiator or an administrator alike; at least 1 (`INVALID_URGE_INTERVAL` otherwise) | `240` |
| `approverDedup` | `Global`, `Contiguous`, `None` — see [Approver Resolution](#approver-resolution) | `Global` |
| `selfApproval` | `AutoApprove`, `RequireApproval` — see [Approving Business Records](#approving-business-records) | `AutoApprove` |
| `emailNotification` | Whether its notices are also emailed — see [Notifications](#notifications) | on |
| `cc` | Who is copied in as the approval is submitted, approved or rejected — see [CC](#cc) | none |

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

Each `FlowNodeType` declares the scenarios that may use it (`FlowNodeType.getScenarios()`); the palette
and the compiler (`INVALID_SCENARIO_NODE`) both read it:

| Node types | Scenarios |
|---|---|
| `Start`, `End`, `Script`, `ReturnValue`, `CreateRecord`, `GetRecord`, `UpdateRecord`, `DeleteRecord`, `QueryRecords`, `CallService`, `CallWebhook` | all four |
| `Timer`, the gateways, `Subflow`, `ValidateData`, `Transform`, `SendEmail`, `SendSms`, `SendInboxNotification`, `QueryAI`, `AsyncTask`, `GenerateFile`, `ForEach` | Approval, Automation |
| `Approval`, `HumanTask` | Approval |

Node-behavior invariants:

- **`Subflow` is synchronous-only**: the orchestrator fails the parent if a child
  enters a waiting state (`TIMER` / `ASYNC`), and recursive subflow invocation is
  rejected. The child must be an Automation flow with a `Subflow` trigger, else the
  parent fails. Model human steps in an Approval flow, not a child.
- **`ParallelFork` branches execute serially** (deterministic order), not
  wall-clock-parallel — the gateway models routing topology, not concurrency.
- **A `Timer` waits one way**: `durationSeconds` (at least 1), `cronExpression`, or `deadlineExpression` (an
  AviatorScript expression resolving to a time) — exactly one, else `INVALID_TIMER_CONFIG`. Its cron speaks a Cron
  trigger's dialect, the scheduler's Quartz (`FlowCron`), so a flow has one cron language: one the scheduler cannot
  run, or that never fires again, is `INVALID_TIMER_CRON`; a deadline that does not compile, `INVALID_TIMER_DEADLINE`.

The graph itself has exactly one `Start` — the entry; a node that merely lacks incoming edges is
not one (`NO_START_NODE` / `MULTIPLE_START_NODES`) — no cycle (`GRAPH_CYCLE`), and every node reachable
from the `Start` along the edges (`UNREACHABLE_NODE`): an unreachable node would never run, and a
`ParallelJoin` it feeds counts its edge among the arrivals it waits for, so every instance would stop
there. A draft may break these rules — saving never validates — but it cannot publish.

### Routing: edge conditions

Conditions live on edges (`FlowGraphEdge`); a node carries none. Every node routes **exclusively** over its
outgoing edges — at most one conditional edge may hold, else the instance fails; none holding takes the one
edge without a condition (the default, at most one per node, `MULTIPLE_DEFAULT_EDGES`) — except
`InclusiveGateway`, which takes every edge that holds plus every edge without a condition, and `ParallelFork`,
which takes all of them (a condition there is not read). `FlowGatewayRouter` decides; the approval path preview
decides the same way.

An edge's condition is one of two kinds, never both (`CONFLICTING_EDGE_CONDITIONS`):

| Kind | Field | Use | Judged |
|---|---|---|---|
| Rule | `condition` — the platform `Filters` JSON, `["days", ">", 3]` / `[[…], "AND", […]]` | The fields of the record the flow starts on: the designer builds it with the model's own filter builder | By `FilterEvaluator`, like a field condition (`requiredWhen`): typed by the record model's fields, `null` ≡ `""`, ordering needs two values (`days < 3` is false for an empty `days`), options by item code, relations by id |
| Expression | `conditionExpression` — AviatorScript | What a rule cannot say: arithmetic, functions, what an earlier node produced | By AviatorScript over the instance's variables, its own semantics (`nil` is smaller than any number) |

A rule needs a flow that starts on a record — a `Submit`, `EntityChange` or `FieldChange` trigger — else
`RULE_WITHOUT_RECORD`. Publishing checks it the way the annotation scan checks a field condition
(`FieldConstraints.checkCondition`), reporting `INVALID_RULE_CONDITION`: a field the model lacks, a to-many
field, ordering an option or a relation, `PARENT OF` / `CHILD OF` (they need a query), a placeholder that does
not resolve, `@mode` / `@userId` (they describe a write). An approval's rule reads only the fields its
approvals route on (*Routing variables*). `GET /flow/designs/{id}/availableVariables?nodeId=&afterNode=true`
lists what the edges leaving a node see: the record's fields as `Trigger` variables, typed, and the node's
own outputs with the upstream ones.

### Runtime Statuses

Defined `FlowExecutionStatus` values:

- `Pending`
- `Running`
- `Waiting` — parked on one or more waits (a pending approval and/or timer / async callback). The
  specific reasons live in the instance's pending-approval list and wait-token list, so a single
  status covers parallel branches suspended for different reasons at once.
- `Rejected`
- `Returned` — sent back to the initiator; ends the instance like a rejection, but its record is a draft
  again. The initiator edits and submits it again, which starts a new instance from the beginning
- `Withdrawn` — taken back by the initiator; ends the instance, and its record is a draft again
- `Cancelled` — ended by an administrator (*terminate*, see [Monitor](#monitor)); a record's approval cancels its
  record with it
- `Completed`
- `Failed` — a step could not run. It stays failed unless an administrator terminates it (→ `Cancelled`); the
  submitter of a record's approval can instead take the record back (see [Approving Business Records](#approving-business-records))

## API Overview

Host applications embedding the engine in-process should integrate through the
`FlowClient` facade (`io.softa.starter.flow.api`) rather than looping back over
REST — actor / initiator / tenant identity is always resolved server-side from the
context, never passed as call parameters. `FlowAutoConfiguration` registers the default
`InProcessFlowClient` only when the application defines no `FlowClient` bean of its own.
See [docs/integration-guide.md](docs/integration-guide.md).

A refused request answers in the framework's envelope (HTTP 200, the code in the body) with its reason as the
message: an action the engine refuses (`FlowActionValidationException`) with `400`, a caller not allowed
(`FlowAuthorizationException`) with `403`, a flow that does not compile with `400` and its diagnostics.
`FlowExceptionHandler` is ordered first (`Ordered.HIGHEST_PRECEDENCE`): Spring takes the first advice that handles
an exception at all, and softa-web's `Throwable` catch-all would otherwise answer these as a bare `500`. Any other
error still reaches that catch-all — logged, answered `500`, its message kept out of the response.

### Design APIs (flow editor)

Base path: `/flow/designs` — the dedicated surface for the graphical editor
(full contract in [`docs/frontend-editor-api.md`](docs/frontend-editor-api.md)).
The generic model API at `/FlowDesign/**` remains available as the platform
data plane and is intentionally not intercepted.

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/designs` | Create a draft: `scenario`, the starting `designJson` (its `trigger` names a record trigger's model), and for an Approval flow its document `modelName` (required; the trigger is made from it). See [A Flow's Start](#a-flows-start-its-trigger-and-the-model-it-names) |
| `GET` | `/flow/approvalModels` | The documents the application approves, each with the names of its approval flows (`flowNames`) |
| `GET` | `/flow/cron/preview?expression=&count=` | A cron expression — a Cron trigger's or a Timer's — as publishing judges it: `valid`, the refusal `message`, its `description` in words, and its next runs (`count`, 1–10, default 5) in the server's `zone` |
| `POST` | `/flow/expressions/preview` | Try an expression — or, with `template`, a text's `{{ }}` placeholders — over sample `variables` on the sandboxed engine flows run with: `valid`, the `result` and its `resultType`, or the `message` why it does not run. Nothing is read or written |
| `GET` | `/flow/designs?keyword=&scenario=&pageNumber=&pageSize=` | Paged draft list (canvas excluded) |
| `GET` | `/flow/designs/{id}` | Load one draft (canvas + optimistic-lock version) |
| `POST` | `/flow/designs/{id}/save` | Auto-save the canvas; stale versions are rejected |
| `POST` | `/flow/designs/{id}/delete` | Delete a flow that was never published; a published one is refused (disable it instead) |
| `POST` | `/flow/designs/{id}/duplicate` | Copy a flow under a fresh name (`"<name> (copy)"` when `newFlowName` is omitted) as an unpublished draft; an approval flow's copy keeps its document and submit condition, to be changed before it is published |
| `POST` | `/flow/designs/validate` | Validate the posted document; diagnostics returned as a success payload |
| `POST` | `/flow/designs/{id}/publish` | Publish the saved draft as a new active revision |
| `POST` | `/flow/designs/{id}/restore?bundleId=` | Restore the draft canvas from a historical bundle snapshot |
| `GET` | `/flow/designs/{id}/status` | Publish status: revision badge, dirty flag, `enabled` |
| `POST` | `/flow/designs/{id}/disable` | Switch the flow off: nothing starts it. Refused while any of its instances has not finished, or while a flow that may still run it calls it as a subflow. See [Enabling And Disabling A Flow](#enabling-and-disabling-a-flow) |
| `POST` | `/flow/designs/{id}/enable` | Switch the flow back on, on the revision that was effective |
| `GET` | `/flow/designs/{id}/availableVariables?nodeId=&afterNode=` | Variables visible to a node (expression autocomplete); `afterNode=true` — to the edges leaving it, its own outputs too |
| `POST` | `/flow/designs/{id}/debugRun` | Compile and run the current draft as a debug bundle (not a sandbox) |
| `GET` | `/flow/designs/{id}/debugRuns` | Debug-run history, newest first |

### Bundle APIs (published revisions)

Base path: `/flow/bundles`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/bundles?flowId=` | Revision list of one flow (or, without `flowId`, the active bundle of every flow of the caller's tenant) |
| `GET` | `/flow/bundles/{bundleId}?include=design` | One revision's summary, optionally with the canvas-complete design snapshot |
| `POST` | `/flow/bundles/{bundleId}/activate` | Make this revision the flow's effective one (rollback = roll-forward) |

### Node Descriptor APIs

Base path: `/flow/nodeDescriptors`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/nodeDescriptors` | List all node descriptors for the editor palette |
| `GET` | `/flow/nodeDescriptors?scenario=Approval` | Filter descriptors by `FlowScenario` |

### Instance APIs

Base path: `/flow/instances` — launch, reads and approval actions on runtime instances.

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/instances/start` | Start an Automation flow whose trigger is `Api`, by `flowId` or a pinned `bundleId`; any other flow is refused |
| `GET` | `/flow/instances/{instanceId}?includeTrace=` | Get one runtime instance (trace excluded by default), with `initiatorName`, its initiator's name (not stored) |
| `GET` | `/flow/instances/{instanceId}/overlay` | Per-node run state for canvas painting |
| `GET` | `/flow/instances/{instanceId}/trace?sinceSequence=` | Incremental trace rows for polling |
| `POST` | `/flow/instances/search` | Paged summaries of the caller's own record approvals, newest first — the initiator filter is always the authenticated user, and automation runs their writes triggered are left out. See [Instance search](#instance-search) for the filters and what each row carries |
| `POST` | `/flow/instances/approve` | Approve a pending approval |
| `POST` | `/flow/instances/reject` | Reject a pending approval; the comment saying why is required |
| `POST` | `/flow/instances/batch/approve` | Approve several of the caller's pending tasks (`tasks`: instance + step, at most 100), each in its own transaction. Returns `FlowBatchActionResult` — succeeded / skipped / failed counts and each task's outcome. See [Batch actions](#batch-actions) |
| `POST` | `/flow/instances/batch/return` | Return several pending tasks to their initiators with one comment (required); a step that allows no return is skipped |
| `POST` | `/flow/instances/batch/reject` | Reject several pending tasks with one comment (required) |
| `POST` | `/flow/instances/transfer` | Transfer a pending approval task |
| `POST` | `/flow/instances/delegate` | Delegate a pending approval task |
| `POST` | `/flow/instances/add-sign-before` | Insert a prerequisite signer |
| `POST` | `/flow/instances/add-sign-after` | Insert a follow-up signer |
| `POST` | `/flow/instances/cc` | CC one or more people (`targetActorIds`) on the caller's pending step. See [CC](#cc) |
| `POST` | `/flow/instances/cc/read` | Mark a CC as read |
| `POST` | `/flow/instances/return` | Return a pending approval to the initiator, ending the instance as `Returned`. Only the step's approver, on a step that allows returns (`returnEnabled`), with a comment saying what to change |
| `POST` | `/flow/instances/withdraw` | Withdraw an Approval instance: its initiator, while it waits on its approvers and the flow's `withdrawPolicy` permits it (`WithdrawPolicyGuard`) |
| `GET` | `/flow/instances/{instanceId}/withdraw` | Whether the caller may withdraw the instance now — `true` exactly when a withdrawal would be accepted, by the same `WithdrawPolicyGuard`. Allowed to whoever may view the instance |
| `POST` | `/flow/instances/urge` | Remind the approvers the approval waits on — its initiator only, on a flow whose `urgeEnabled` is not turned off. Each waiting step is urged at most once per the flow's `urgeIntervalMinutes` (default 240), by the initiator or an administrator alike; each step urged gets its own `URGE` history entry, and when every step is still inside the interval the urge is refused. Returns `FlowUrgeStatus`, not the instance |
| `GET` | `/flow/instances/{instanceId}/urge` | Whether the caller may urge the approval now, and if not, when (`FlowUrgeStatus`) — never, for the initiator of a flow that turns `urgeEnabled` off. Allowed to whoever may view the instance |
| `POST` | `/flow/instances/comment` | Add a comment audit entry while the instance runs — its participants only: the initiator and the people it gives or gave a task to. An instance that has ended takes no more comments. Returns `true`, not the instance |
| `GET` | `/flow/instances/{instanceId}/nodes/{nodeId}/formPermissions` | Get field-level form permissions |
| `GET` | `/flow/instances/by-record?modelName=&rowId=` | A business record's approval history: its approval instances newest first, with the steps still waiting, the approvers yet to act and the approval actions, every user named (names are resolved only once the caller is allowed); a failed instance says where and why it stopped (`failure`). Allowed to whoever took part in its approvals or can read the record |
| `GET` | `/flow/instances/preview?modelName=&rowId=` | What submitting the record would do now, without submitting it: the Submit flow it would get and its approval steps, each with the approvers who would be asked or why it passes on its own; a refusal, no applicable flow or several are reported, a route decided only once the approval runs is reported as undecided. For a record in approval, the rest of its path (`InProgress`): the step it waits on now, then those after it. Nothing is started or written. Allowed to whoever can read the record (see [Previewing a record's approval](#previewing-a-records-approval)) |
| `GET` | `/flow/instances/{instanceId}/record` | The business record a record's approval runs on — any other instance is refused, since its record was named freely rather than submitted — for its initiator and the people it gives a task to: read past their own row permissions. Every field, dynamic ones included, but the sensitive ones the caller may see on no row; the field permissions of the waiting steps the caller approves are laid over that — what they list as `Readonly` / `Editable` / `Required` shows, sensitive or not, and what they mark `Hidden` does not. The initiator sees every field but those unseen sensitive ones; anyone else with no waiting step also misses what any approval step hides. Read as the model read API returns a record (relations and options as references, masked fields masked), with each one-to-many's rows in full — an expense claim comes with its lines |

An action's `comment` is at most 256 characters, as a batch action's comment and an administrator's reason are; a
longer one is refused (`400`) before anything runs.

Who may view an instance: its initiator and the people it gives a task to (approvers pending or done, CC
recipients, transfer, delegation and add-sign targets) by default; anyone else through the standard role and
data-scope configuration — they may read the `FlowInstance` row with their own permissions, or, for a
record's approval, read the record itself.

The defaults need no grant on the flow models, which no business role holds. The engine's own bookkeeping —
loading and saving instances, the approval ledger, the task and branch projections, delegation and auto-CC
rules — runs past the caller's data permissions: who may act is the action handlers' call, who may see the
access guard's. The current user's own lists — inbox counts, pending / completed / CC tasks and the inbox
(`/flow/approvalTasks`), their approval history and sent CCs (`/flow/approvalRecords`), their delegations,
and `POST /flow/instances/search`, with their `FlowClient` counterparts — carry `@SkipPermissionCheck`:
pinned to the caller, row scope could only empty them. The per-instance reads (`.../instance/{instanceId}`,
the instance detail endpoints) are not waived, because the guard judges everyone but the initiator and the
task holders by their own permissions.

### Instance search

`POST /flow/instances/search` (the caller's own approvals) and `POST /flow/monitor/instances/search` (across
initiators, see [Monitor](#monitor)) take the same `FlowInstanceSearchRequest` and return the same rows, newest
first, paged by `pageNumber` (default 1) and `pageSize` (default 50). Filters combine with AND:

| Filter | Matches |
|---|---|
| `flowId`, `status`, `modelName`, `rowId` | The instance's own columns (`rowId` with `modelName`) |
| `createdFrom` / `createdTo` | When it started — for a record's approval, when the record was submitted |
| `initiatorId` | The monitor search only; the caller's own search always pins it to them |
| `subjectId`, `subjectDeptId` | A record's approval by the employee it is about, or by that employee's department, as at submission |
| `effectiveDateFrom` / `effectiveDateTo` | A record's approval by the date the record takes effect |
| `recordCode` | A record's approval by the record's own number (contains) |
| `awaitingRevision` | Approvals returned to their submitter that neither a later submission of the record replaced nor the record's cancellation closed (`superseded` unset) — records still waiting to be edited and submitted again |

A row (`FlowInstanceSummaryView`) leaves out the heavy state columns and the trace — fetch the instance or its
overlay for those. It carries the flow, title, initiator, status and the steps the instance waits on now
(`currentSteps`, each with the approvers still to act), every user named, and for a record's approval what its
submission recorded (*Approving Business Records*): `subject`, `subjectDept`, `effectiveDate`, `recordCode`, and
`awaitingRevision`. On the caller's own approvals that wait on their approvers, `withdrawable` and `urge` say
whether they may withdraw or urge them now; on the monitor search, `urge` says whether the caller may urge as an
administrator — the flow's `urgeIntervalMinutes` decides, whatever its `urgeEnabled` says. `recordDeleted` marks an
approval whose record is gone — a draft deleted after a return or a withdrawal — `recordCancelled` one whose record
was cancelled after it ended, and `effectStatus` says where an approved record's change stands, for a model whose
approval takes effect later (`ModelApprovalHandler.effectStatuses`).

### Automation APIs

Base path: `/flow/automation` — trigger firing and transient runs; none of these reads or
acts on an existing instance.

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/automation/trigger` | Fire a trigger event and start matching flows. `Submit`, `Cron`, `Api` and `Subflow` events are refused: those flows start by record submission, on their own schedule, by id, or from a parent flow |
| `POST` | `/flow/automation/onchange` | Run `COMPUTE` flows transiently and return variable diffs |
| `POST` | `/flow/automation/validate` | Run `VALIDATION` flows transiently against candidate row data; returns each flow's declared outputs keyed by `flowId` |

Async-task and timer resumes have no REST endpoint: `FlowAsyncTaskConsumer`,
`FlowTimerConsumer` and the due-timer sweep of the maintenance cron call
`FlowRuntimeEngine` in-process.

`actorId` / `initiatorId` / `tenantId` are **never** sent by clients: every action
request stamps them server-side from the login context (`@JsonProperty(READ_ONLY)`),
and all query endpoints resolve the current actor from the context. The in-process
`FlowClient` resolves the actor the same way and calls the same services as these
endpoints.

## Approval Projection APIs

### Approval Tasks

Base path: `/flow/approvalTasks`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/approvalTasks/counts` | Inbox badge counts for the current actor (`{pendingApprovals, unreadCc}` — same filter definitions as the paged `/pending` and `/cc?read=false` queries) |
| `GET` | `/flow/approvalTasks/pending?flowId=&instanceId=&nodeId=&pageNumber=&pageSize=` | Pending tasks for the current actor, newest first (paged: returns `Page`) |
| `GET` | `/flow/approvalTasks/completed?flowId=&instanceId=&nodeId=&pageNumber=&pageSize=` | Completed tasks for the current actor (paged) |
| `GET` | `/flow/approvalTasks/cc?read=&flowId=&instanceId=&nodeId=&pageNumber=&pageSize=` | CC tasks for the current actor (paged; `read=false` unread, `read=true` acknowledged, omit `read` for both) |
| `GET` | `/flow/approvalTasks/inbox?flowId=&instanceId=&nodeId=&includeCompletedApprovals=&pageNumber=&pageSize=` | Unified inbox view (pending approvals + CC; optional completed approvals) |
| `GET` | `/flow/approvalTasks/instance/{instanceId}` | All tasks for one runtime instance (participants / initiator only) |

Each task (`FlowApprovalTaskView`) carries its holder's name (`actorName`) and comes with its instance's context —
`instanceTitle`, `modelName` / `rowId`, `instanceStatus`, the `initiator` and `submittedTime`, and what a record's
approval recorded of its record (`subject`, `subjectDept`, `effectiveDate`, `recordCode`), every user, employee and
department named — and with what its step's configuration says about acting on it, read from the revision the
instance runs on: `returnAllowed` (the step's `returnEnabled`), `fillIn` (the step makes a field required: the
approver fills it in and submits, rather than just approving) and `batchable` (open to its approver now — pending,
not blocked by an add-sign, the instance waiting — on a step that is not `fillIn`). See [Batch actions](#batch-actions).

The tasks are a projection of the approval history, brought up to date on every change of the instance:

- an approval task per approver and turn. It closes with what its holder did — `Approved`, `Rejected`, `Returned`,
  `Transferred`, `Delegated` — even when the same action went on to record more (the next step approving itself, a
  CC as the approval ended); a task its holder did not act on closes `Withdrawn` after a withdrawal, else
  `Canceled` (another approver ended the step, an administrator terminated the approval). Closed, it stays as it
  is: the others' later votes do not copy it;
- a CC task per recipient, step and cycle: `Pending` until they read it, then `Read`, ending when they read it.

### Approval Records

Base path: `/flow/approvalRecords`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/approvalRecords/instance/{instanceId}` | Approval history by runtime instance |
| `GET` | `/flow/approvalRecords/history` | Actor-scoped approval history |
| `GET` | `/flow/approvalRecords/cc/sent` | Sender-side CC history |

An entry (`FlowApprovalRecord`, `FlowApprovalRecordView`) records its `action` (`ApprovalActionType`: `APPROVE`,
`AUTO_APPROVE`, `REJECT`, `TRANSFER`, `DELEGATE`, `ADD_SIGN`, `CC`, `READ`, `RETURN`, `WITHDRAW`, `URGE`, `COMMENT`,
`TERMINATE`), its `actorId` and, for an action done on the actor's behalf, `operatorId` — the administrator who
reassigned the actor's task (a `TRANSFER` entry from the approver to the new one); empty when the actor acted
themselves. An instance's history (`/instance/{instanceId}`) names its people: `actorName`, and `targetActorName`
for the one an entry hands to (the CC recipient, the transfer or delegation target).

### CC

An approval flow's CC rules are part of its definition (`cc`, a list of `FlowCcRule`), set in the flow's settings
and published with each revision — an instance follows the rules of the revision it runs on. A rule says when:

- `OnSubmit`: as the approval is submitted.
- `OnApproved`: as it ends approved.
- `OnRejected`: as it ends rejected (a rejection the step's reject threshold does not yet carry is not the end).

and who: named users (`recipients`), or a source to find them by (`recipientSource`, in an approval step's
`approverSource` shape — the subject's supervisor, a role, a department's members…), never both; found the way a
step's approvers are (`ApproverResolutionService.resolvePeople`). CC is the approval's, not a step's: a step that
should involve someone else has them as its approvers.

An automatic CC is recorded like a manual one: one `CC` entry per recipient in the approval history. The entry
has no actor, since no person sent it. It sits on the start node (`OnSubmit`, and `OnApproved` when every step
passed on its own) or on the step whose action ended the approval, in that node's current cycle. So the
recipient:

- has a CC task in the inbox, which they mark read, and an inbox notice;
- is a participant of the instance: they open it and comment on it.

A recipient already CC'd on the same node and cycle, by a rule or by hand, is not CC'd again.

An approver whose turn it is — one of the pending step's approvers, still to vote and not waiting on a signer added
before them — CCs people by hand with `POST /flow/instances/cc`: one or more (`targetActorIds`) in one action, with
an optional comment. The people who already see the approval from the step are skipped — its approvers, the actor
among them, and anyone CC'd on it this cycle — and the rest get one `CC` entry each, with the approver as its actor,
and one inbox notice together. The action is refused when nobody is left.

### Delegation

Base path: `/flow/delegations`

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/delegations` | Create a delegation rule |
| `GET` | `/flow/delegations/my` | Delegations created by the caller (delegator resolved from the login context) |
| `GET` | `/flow/delegations/to-me` | Active delegations assigned to the caller (delegate resolved from the login context) |
| `POST` | `/flow/delegations/{id}/cancel` | Cancel one of the caller's delegation rules; another user's is refused (403) |

A rule is in force (`active`) from its creation until it is cancelled, or, with `autoExpire`, until its end time
passes; between those, only the tasks that arrive within its period (`startTime` / `endTime`, either open) go to the
delegate — tasks already waiting stay where they are. It needs the approver and the delegate, two different people,
and a period with both ends must end after it starts; otherwise it is refused (`400`), whoever creates it. The lists
return `FlowDelegationView`: the rule with `delegatorName` and `delegateName`.

### Monitor

Base path: `/flow/monitor` — the administrators' console. No role is built in: each endpoint is granted like any
other, and what the console reads or acts on is the caller's own permissions on `FlowInstance` — their roles' grant
and data scope; taking part in an instance is not enough. A department scope (`DEPT_SUBTREE`,
`MANAGED_DEPARTMENTS`) narrows by `FlowInstance.departmentId`, the subject's department at submission — a plain
department-id column the permission-starter scopes filter by the ids of the departments in range — so a role
scoped to the departments it manages sees and administers their approvals; an automation run belongs to no
department. Opening another user's instance (detail / overlay / trace / approval history) follows the viewing rule
under [Instance APIs](#instance-apis), as for any viewer who is not a participant.

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/monitor/health` | Flow runtime health snapshot (per-status instance counts, overdue timers) |
| `POST` | `/flow/monitor/instances/search` | Cross-initiator paged instance summaries, read with the caller's own permissions: the `FlowInstance` rows their data scope reaches. Honors the request's `initiatorId` filter when present; otherwise as [Instance search](#instance-search) |
| `POST` | `/flow/monitor/instances/urge` | Urge an approval as an administrator rather than its initiator: whoever may read the instance's `FlowInstance` row with their own permissions, whether or not the flow's `urgeEnabled` lets its initiator urge. The flow's `urgeIntervalMinutes` applies — a step urged within it, by anyone, is not urged again. Returns `FlowUrgeStatus` |
| `POST` | `/flow/monitor/instances/terminate` | End each instance named (`instanceIds`, at most 100) that is running, waiting or failed, with one `reason` (required): its pending approvals close, it ends `Cancelled` — a record's approval cancels its record — and the history records a `TERMINATE` entry with the reason, which the initiator is sent. Only instances the caller's permissions read; each in its own transaction. Returns `FlowBatchActionResult` |
| `POST` | `/flow/monitor/approvers/reassign/preview` | How many of an approver's pending approval tasks (`fromActorId` → `toActorId`), on the instances the caller's permissions read, a reassignment would move: `FlowReassignPreview` (`tasks`, `instances`) |
| `POST` | `/flow/monitor/approvers/reassign` | Move each of those tasks to the new approver, with a `reason` (required), each in its own transaction: recorded as the approver's `TRANSFER` with the caller as `operatorId`; the new approver is given the task, the initiator is told who now holds it. Only the steps the instances wait on now move — a step not reached yet resolves its approvers when reached; CC tasks stay. Returns `FlowBatchActionResult` |
| `POST` | `/flow/monitor/delegations` | Create a delegation rule on an approver's behalf (the body names the `delegatorId` and the `delegateId`), checked as the approver's own is (see [Delegation](#delegation)): their tasks arriving from then on go to the delegate, and the approver sees the rule among theirs. It affects all of the approver's tasks whatever the caller's data scope, so it is a permission of its own |
| `GET` | `/flow/monitor/delegations` | Every delegation rule of the tenant, newest first, as `FlowDelegationView` (with both people's names) |
| `POST` | `/flow/monitor/delegations/{id}/cancel` | Deactivate any delegation rule of the tenant |

### Event Logs

Base path: `/flow/events`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/events?flowId=&sourceModel=&sourceRowId=&instanceId=&success=&eventTimeFrom=&eventTimeTo=&pageNumber=&pageSize=` | Paged event log, newest first (filters combine with AND; list rows exclude the parameters payload) |
| `GET` | `/flow/events/{id}` | Single event including the raw trigger-parameters payload |

## Approval Model

### Approval And Reject Thresholds

- Approval modes (`config.approvalMode`): `AnyOne` (default), `Unanimous`, `MinCount`, `Percentage`
- Reject modes (`config.rejectMode`): the same four
- A config holds these by their JSON values, as above; the enum constant names (`ANY_ONE`) are not read.
- One actor can only cast one effective vote per cycle.
- Compile-time validators reject impossible threshold combinations.

### Approver Resolution

Approval nodes support:

- static approvers via `config.approvers`
- dynamic approvers via `config.approverSource`
- empty-approver strategies via `config.emptyApproverStrategy`: `Error` (default), `AutoApprove`, `Skip`

A value a config cannot be read with — a constant name such as `ERROR`, which an earlier designer offered — is
reported against its field (`UNREADABLE_CONFIG`) instead of failing the validation; choose it again.

Supported dynamic source types:

- from start variables: `VariableList`, `Expression`, `InitiatorManager` (`initiatorManagerId`), `Role` (`roleApprovers`)
- from the organization: `Supervisor` (the N-th level supervisor), `DeptLeader` and `DeptHrbp` (of a
  department), `RoleQuery` (holders of the roles named by `roleIds`, by `roleCodes`, or both — optionally
  narrowed to a department), `Position`, `Department` (its members)

The organization is read relative to the record's **subject** — the employee it is about (*Approving Business
Records*) — so a request filed on someone's behalf goes to their approvers, not the submitter's. `Supervisor`
takes `relativeTo`: `SUBJECT` (default) or `INITIATOR`. The department-based sources take one `deptScope`:

| `deptScope` | Department | Default of |
|---|---|---|
| `SUBJECT_DEPT` | the subject's | `DeptLeader`, `DeptHrbp` |
| `INITIATOR_DEPT` | the submitter's | |
| `SPECIFIC_DEPT` | the one named in `deptId` (`deptIds` for `Department`) | `Department` |
| `VARIABLE_DEPT` | the id(s) held by the variable named in `deptVariable` — a transfer's receiving department | |
| `ALL` | none: every holder (`RoleQuery` only) | `RoleQuery` |

A department that cannot be told — no subject department, an empty variable — resolves to nobody, never to
every holder; the step then follows its empty-approver strategy. An instance without a record (a debug run)
has no subject and starts from its initiator. The compiler refuses a scope a source does not take and a scope
missing what it needs (`INVALID_DEPT_SCOPE`, `MISSING_DEPT_IDS`, `MISSING_DEPT_VARIABLE`, `INVALID_RELATIVE_TO`).

Approvers are **login account ids**. The organization is read through the `OrganizationService` SPI, whose
default `FlowOrgService` follows the HR conventions permission-starter already reads, so an application on
those models configures nothing:

| Purpose | Model.field |
|---|---|
| Account → employee (the initiator's) | `Employee.userId` |
| Name, department, position | `Employee.fullName`, `departmentId`, `jobPositionId` |
| Supervisor (an employee id) | `Employee.piEmployeeId` |
| Department leader, HRBP (employee ids), parent, name | `Department.picEmpId`, `hrbpEmpId`, `parentId`, `name` |
| Role holders, role codes | `UserRoleRel.userId` / `roleId`, `Role.code` |

An approver needs an account that signs in: one whose `UserAccount.status` is in
`softa.flow.org.approver-account-statuses` (default `Active`). An employee without an account counts as
nobody, and so does one whose account does not sign in — not yet invited (`Pending`), invited but not accepted
(`Invited`), `Frozen`, or ended because they left (`Deactivated`) — so a step resolving only to such employees is
empty and follows its empty-approver strategy: a subordinate's request no longer waits on a supervisor who
left. The supervisor chain runs on through them (level 2 is still their supervisor). A role holder is an account
and approves even without an employee profile, provided it signs in. A department without a leader (or HRBP)
who can approve defers to its parent's. Looking users up by id, to name them, still finds every account. The
status is not checked when the application has no `UserAccount` model. Lookups skip row scope — who approves
must not depend on what the initiator may see. Every model and field name can be overridden under
`softa.flow.org.*` (`OrganizationServiceProperties`: `account-model`, `account-status-field` and
`approver-account-statuses` among them); an application with another organization system provides its own
`OrganizationService` bean. Tasks already assigned to someone who has since left stay theirs until an
administrator reassigns them.

Role codes, unlike role ids, are the same in every tenant, so a flow seeded to many tenants names its built-in
roles by code (`roleCodes`); the designer offers both lists, since roles an administrator creates have no code
and are picked by id.

Approver dedup (`approverDedup`): the default policy is **`Global`** — a user who already approved an
earlier node is auto-passed on later nodes (recorded as `AUTO_APPROVE`). Residual
risk: a mid-flow form edit is still auto-endorsed by that earlier approval; set the
policy to `Contiguous` or `None` on flows where a re-look matters.

### Approver's writes

Once the actor is validated as the node's approver, the approval's form edits and every node it resumes
run with the actor's own row scope set aside. Approving is the grant: an approver rarely holds update on
the record they approve, and with row scope granted per action (permission-starter) that would refuse
the approval itself. Host endpoints that apply an approval outside the engine do the same, after an
entry check that the approver can see the request (`@RequirePermission`). A record's write rules
(`ModelWriteValidator`) therefore see these writes as the system's: the approval lock
(`ApprovalLockValidator`, see *Approving Business Records*), which freezes a record while it is in
approval, still lets through the fields a node marks editable.

Set aside means **every** permission check, not only row scope — sensitive-field write checks included.
The node's form configuration (its field permissions, `formPermissions`) is the only authorization for the
fields the approver edits. Only the fields it marks `EDITABLE` or `REQUIRED` are written back; any other key
in the approve request's `formData` — a `HIDDEN` or `READONLY` field, or one the node does not list — is
dropped, and a node with no `formPermissions` writes nothing. A `REQUIRED` field must hold a value once the
edits land — the one sent, or else the one the record stores — so an approve that sends nothing is still
refused when the record lacks it. After the write, a record's approval reads its routing variables again from
the edited record — its own fields, then its handler's `submission` on top — and they replace the ones the
flow started with: a later route sees what the approver changed, a cleared field included. An approver whose roles hold no sensitive
field set covering a field can therefore still write it if the node's form exposes it as editable. Flow
designers must not make a sensitive field (pay, bank details, identity numbers, …) editable on an approval
form unless every approver that node can resolve to is meant to edit it; show it read-only, or leave it
off the form.

### Additional Approval Actions

- transfer
- delegate
- add-sign-before
- add-sign-after
- cc
- read-cc
- return (to the initiator; ends the instance)
- withdraw
- urge
- comment
- approve / return / reject in a batch (an approver over several of their tasks)
- terminate and reassign (an administrator, from the [Monitor](#monitor))

### Batch actions

What a step's approver may fill in when they approve is its `formPermissions` (`EDITABLE` / `REQUIRED` fields);
`config.returnEnabled` decides whether the step may be returned to the initiator at all.

`POST /flow/instances/batch/{approve,return,reject}` (`BatchApprovalExecutor`) runs one approver action over up to
100 of the caller's pending tasks with one comment — required to return or reject. Each task is its own engine
call and so its own transaction: one refused task changes nothing for the others, and every engine rule holds as
for a single action. A batch fills in nothing, so before the engine is asked it skips a task whose step makes a
field `REQUIRED`, and, for a return, a step without `returnEnabled` — the same rule that
sets a task's `batchable` flag. The result (`FlowBatchActionResult`) gives the succeeded / skipped / failed counts
and each task's outcome with the reason, in request order.

### Timeout Handling

An approval node's `timeout` gives its step a deadline, `timeoutHours` after the step opens; without
`timeoutHours` the step has none and the rest of `timeout` is ignored. At the deadline `timeoutStrategy` applies:

| Strategy | At the deadline | Uses |
|---|---|---|
| `Remind` (default) | Reminds the approvers still to act, then every `remindIntervalHours`, at most `maxRemindTimes` times (3 unless set) — with no interval, once. No automatic action | `remindIntervalHours`, `maxRemindTimes` |
| `AutoApprove` | Approves for the approvers still to act | — |
| `AutoReject` | Rejects the approval | — |
| `Escalate` | Hands the step to `escalateToUserId`, with a new deadline | `escalateToUserId` (required) |

Nothing is sent before the deadline. Reminders belong to `Remind`: set on another strategy they fail the compile
(`REMINDERS_OUTSIDE_REMIND`), and the designer clears them when another strategy is chosen; an interval or a most
below 1 fails it too (`INVALID_REMIND_INTERVAL`, `INVALID_MAX_REMINDERS`). The reminder is an inbox notice
("Approval overdue"), not emailed.

`ApprovalTimeoutScheduler` handles the steps past their deadline against persisted pending approvals.

It runs off a cron row, `flow_approval_timeout_handle`. The starter's maintenance crons (approval timeouts, delegation
expiry, timer sweep, stuck-instance scan, debug bundle purge, automation run purge) ship switched off in
`data-system/SysCron.FlowMaintenance.json`. The app lists that file under `platform` in its
`seed-manifest.yml`, or the rows never exist; then switch on the ones it uses. See the
[seed-starter README](../seed-starter/README.md).

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
  (type-aware for data executors, recursive interpolation for free-shape payloads). Publishing checks every
  placeholder in any node's settings the way it will run (`TemplatePlaceholderValidator`): an expression that
  compiles, a variable path, or a reserved reference `{{ @name }}` — closed and not empty — else
  `INVALID_TEMPLATE_PLACEHOLDER`, naming the node and the setting (`to[1]`, `templateVariables.owner`)
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

## Approving Business Records

A business record — a leave request, an expense claim — is approved by an Approval flow of its document: its
trigger is a `SubmitTrigger` naming the record's model (`modelName`) and, optionally, which of its records it takes. Its model extends `ApprovalModel` (`entity`, in place of
`AuditableModel`), its controller extends `ApprovalModelController` (in place of `EntityController`), and the
host implements one `ModelApprovalHandler<T>` per model (`runtime/spi`, a Spring bean).
`ApprovalModelController` gives every such model the same three endpoints, granted per model like any other:
`POST /{Model}/submit?id=`, `POST /{Model}/withdraw?id=` (body `{comment}`, optional) and
`POST /{Model}/cancel?id=`, through `FlowClient`. What a submission would do is answered for every model by
`GET /flow/instances/preview`.

Every record has a **subject**: the employee it is about, whom the organization-relative approver sources
start from (*Approver Resolution*). It is the model's `employeeId` unless the handler's `subject()` names
another field (`Transfer::getTransfereeId`); either way a many-to-one onto the employee model
(`softa.flow.org.employee-model`), which the boot checks. A record whose subject is empty is not submitted.
The submission carries it to the flow as the start variable `_subjectId`.

**Routing variables.** An approval starts with every field of the record a route can compare, under the
field's name (`RecordVariables`): its own columns of a plain type — text, numbers, booleans, dates and times
(as ISO text), options and multi-options (item codes), multi-strings, to-one relations (the id) — less its
identity, bookkeeping and approval state (`id`, `tenantId`, `version`, `deleted`, the audit fields,
`approvalStatus`, `submitterId`, `submittedTime`) and any field declared sensitive — encrypted, or masked when
read — since an instance's variables are not read through the record's field permissions; a field with no value
is left out. The handler's
`submission` adds what it works out from the record on top — a value read through another model, such as the
company's currency — and one of the same name replaces the field's. The designer offers the same fields, so a
rule condition (*Routing: edge conditions*) can read every one of them.

The framework keeps the approval state, the same for every model; the handler holds what only the business
knows:

| Framework | Handler |
|---|---|
| The approval fields — `approvalStatus` (`ApprovalStatus`), `submitterId`, `submittedTime` — and every write of them | `validate(record)`: the checks a submission must pass — who may submit the record, what it must hold |
| The one state machine below: only a draft is submitted or cancelled; one approval of a record at a time | `validateCancel(record)`: who may cancel the draft beyond seeing it, such as only its claimant |
| Freezing every record that is not a draft (`ApprovalLockValidator`) | `submission(record)`: an `ApprovalSubmission(title, variables, effectiveDate, code)` — the instance title and the routing variables it adds to the record's own fields (*Routing variables*), worked out from the record alone, and, when the model has them, the date the record takes effect and its own number, which the approval lists show and filter on |
| Writing each result back, once | `afterSubmitted(record)`: what a submission sets off, such as reserving a leave balance |
| What a submission records on the instance: subject, department, effective date, number | `afterOutcome(record, outcome)`: the follow-up actions of a result, such as deducting the balance — or releasing it after a rejection or a cancellation |
| | `effectStatuses(rowIds)`: for a model whose approval takes effect later, where each approved record's change stands (`RecordEffectStatus`: `Scheduled`, `Applied`, `Failed`), read from the records themselves, never guessed from dates. Empty by default, for a model whose approval takes effect at once |

Every approval model follows one state machine:

| `approvalStatus` | What may happen | Where it goes |
|---|---|---|
| `Draft` | Edited; submitted; cancelled by its applicant | Submit → `Pending`; cancel → `Cancelled` |
| `Pending` | Frozen. Approvers approve, reject or return it; its submitter withdraws it within the flow's `withdrawPolicy`; an administrator terminates it | Approved → the next step or `Approved`; rejected → `Rejected`; returned or withdrawn → **`Draft`** again; terminated → `Cancelled` |
| `Approved`, `Rejected`, `Cancelled` | Final and frozen: never edited, submitted or cancelled | — |

A return or a withdrawal is not a status a record stays in: the record is a draft again, to edit and submit as a
new approval, and its approval history keeps what happened. An administrator terminating its approval
(`POST /flow/monitor/instances/terminate`) makes the record `Cancelled`. What happens to a record after its
approval — paid, in effect, voided — is not an approval status and goes in the model's own status field; where a
change takes effect later, the handler's `effectStatuses` tells the monitoring console how it stands. The boot
fails when a handler's model lacks the three fields in its metadata.

The flow's `withdrawPolicy` decides until when the submitter may withdraw: `Never` (the default), `BeforeFirstAction`
— until an approver first approves, rejects, returns, transfers, delegates or adds a signer at any step; an
automatic approval does not count — or `UntilCompleted`. Whatever the policy, only the initiator withdraws, and
only while the approval waits on its approvers. `WithdrawPolicyGuard` is the one rule: the withdraw action
enforces it, and the preview, `GET /flow/instances/{instanceId}/withdraw` and the instance search's `withdrawable`
report it, so a Withdraw button is offered exactly when the withdrawal would be accepted.

| `FlowClient` | What happens |
|---|---|
| `submitRecord(modelName, rowId)` | The submitter must read the record with their own permissions; only a draft with no approval in progress is submitted. `handler.validate` then checks the whole record as the submitter, and `handler.submission` returns the instance title and the routing variables it adds to the record's fields. The flow is chosen among the tenant's enabled Approval flows of the model by their submit conditions, read over the variables the approval would start with — exactly one must hold, else the submission is refused naming why. The record becomes `Pending`, with its submitter and the time, `handler.afterSubmitted` runs, and the record's approval starts. All in the caller's transaction. Every submission starts a new approval from the beginning — after a return too, on the revision effective now — and marks the record's earlier approvals `superseded` |
| `withdrawRecord(modelName, rowId, comment)` | Withdraws the waiting approval (the engine checks the actor is the initiator and the flow's `withdrawPolicy` permits it); the record is a draft again. When the record's approval **failed**, its submitter takes the record back the same way — the handler is told it was withdrawn; the failed instance stays for flow monitoring. Refused when the record has no approval in progress |
| `cancelRecord(modelName, rowId)` | Cancels a draft for good: the applicant must read the record with their own permissions, it must be a draft, and `handler.validateCancel` must pass. The record becomes `Cancelled` and `handler.afterOutcome` gets a `Cancelled` result with no instance; the record's earlier approvals are marked `superseded`, so one returned no longer waits for the record to be edited. In the caller's transaction |
| `recordApprovals(modelName, rowId)` | The approval history, as `GET /flow/instances/by-record` |
| `previewRecord(modelName, rowId)` | The approval a submission would start, as `GET /flow/instances/preview` — see below. Call it outside a transaction |

Only the instance a submission starts is the record's **approval** (`FlowExecutionState.recordApproval`): a
debug run of the same flow on the same record is not, and is never written back (an API start of an Approval
flow is refused — its trigger is `Submit`). A record has
at most one approval in progress — `FlowInstance.openRecordId` holds the record's id while its approval is
unfinished, under a unique index with the model, so a second submission is refused even when two arrive at
once.

A submission also records on its instance, as they stand at submission, what the approval lists show and filter
on (see [Instance search](#instance-search)): `subjectId` — the employee the record is about — and `departmentId`,
that employee's department (`OrganizationService.getDeptIdOfEmployee`, read past the submitter's permissions),
the department data scopes narrow by (see [Monitor](#monitor)); `effectiveDate` and `recordCode` from the
handler's `ApprovalSubmission`. Each submission marks the record's earlier approvals `superseded`, so an approval
`Returned` and not superseded is one whose record still waits to be edited and submitted again
(`awaitingRevision`).

Results reach the record through one listener, `ApprovalOutcomeListener`, and one writer,
`ApprovalStatusWriter`: when a record's approval completes, is rejected, returned, withdrawn or
cancelled (terminated by an administrator), the writer sets `approvalStatus` to the result — `Draft` after a
return or a withdrawal — and calls `handler.afterOutcome(record, outcome)` with
the type, the deciding user and their comment (none for an automatic approval; the administrator and their reason
for a termination) — in the action's transaction,
with permission checks skipped, so a handler that throws vetoes the action. A result lands only on a record
still `Pending`: a finished instance saved again (on a comment or a CC read) changes nothing, and
`afterOutcome` runs once per result. Keep it to writes in this database that belong with the result; anything
that leaves it — a payment, an external system — goes after the commit. A failed instance writes nothing and
leaves its record `Pending`, with two ways out: its submitter takes the record back by withdrawing it (a draft
again), or an administrator terminates the approval (the record `Cancelled`). The record becomes `Pending` before the instance starts
because a flow with no approval step completes while it starts. A step whose approver resolves to nobody fails at runtime under its empty-approver strategy
(`Error` by default); the record's approval preview shows where before anyone submits.

On a submit flow the record's own people are approved for by default ("本人是审批人时"): when a step's
approvers include the submitter, or the user of the employee the record is about, they are counted as having
approved — recorded as `AUTO_APPROVE` ("Auto-approved (submitter)" / "Auto-approved (subject)") in the
approval history — and the step's approval mode decides the rest: an any-one step passes, an all-approve step
still waits for the others. The flow setting `selfApproval` decides it: `AutoApprove` (the default) or
`RequireApproval`, which asks them like any other approver. An empty step whose strategy is `AutoApprove` is
recorded the same way, with no actor ("Auto-approved (no approver)").

### The approval lock

`ApprovalLockValidator` (a `ModelWriteValidator`, order 10) holds at the write roots, where every write
lane meets — the record's form, the generic endpoints, an import, a flow's write node:

- a record that is not a draft — `Pending`, `Approved`, `Rejected`, `Cancelled` — is neither changed nor
  deleted, and neither is anything it owns: the records of a model whose to-one field straight onto it cascades
  its deletion (`onDelete = CASCADE`), like an expense claim's lines — nor is a new one filed under it;
- the approval fields are the framework's: a new record is a `Draft` with no submitter, and no write changes
  them. Sending back the stored value is not a change.

Writes made with permission checks off are exempt: the writer's own, the fields an approval step lets its
approver edit (*Approver's writes*), the handler's follow-up actions. The lock only says when nobody may change
a record; who may change it otherwise — its owner, in which of its other states — stays with the model's own
validator. The approval fields are not `readonly` in metadata, which would drop the framework's own writes too;
a form shows them read-only.

### Previewing a record's approval

`GET /flow/instances/preview?modelName=&rowId=` (`FlowClient.previewRecord`) answers what submitting the record
would do now, without submitting it — for a record page that shows the approval path before anyone presses
Submit. It makes the submission's own checks and choice of flow: the record's approval status and its
approvals, `handler.validate` and `handler.submission` with the requester's own permissions, the submit
triggers. A refusal, no applicable flow or several are reported in `status` (`Refused`, `NoFlow`,
`SeveralFlows`, with the flows that apply in `candidates`) and `message` — the message the submission would be
refused with — rather than raised. `afterSubmitted` is never called.

For a record whose approval is in progress, the preview is the rest of that approval (`InProgress`): the step
it waits on now, as it stands (`current`, with the approvers still asked), then the steps after it, walked as
below from the instance's own variables and approvals on a copy of the instance — never the instance itself.
`withdrawable` says whether the requester may withdraw the record now: its submitter, while the approval waits
on its approvers and the flow's `withdrawPolicy` permits it (`WithdrawPolicyGuard`), or after its approval
failed — so a Withdraw button needs no rule of its own.

When one flow applies (`Ready`), `ApprovalPathPreviewer` walks it from its start node the way the engine runs it,
every approval step assumed approved: routes are decided by `FlowGatewayRouter` over the variables the
submission would start with, approvers by `PendingApprovalFactory` (auto-delegation included) and automatic
approvals by `ApproverDedupService`, on a state that is never saved. `steps` lists the approval steps in the
order the approval reaches them, each with an `outcome`:

| Outcome | The step |
|---|---|
| `Waiting` | Waits for `approvers`, of whom `requiredApprovals` must approve |
| `AutoApproved` | Passes on its own: `autoApprovals` counts as having approved the submitter (`Submitter`), the employee the record is about (`Subject`) — under the flow's `selfApproval` — or someone who approved an earlier step, under the flow's approver dedup (`EarlierApproval`) |
| `NoApproverApproved`, `NoApproverSkipped` | Resolves to nobody; its empty-approver strategy approves it on record or skips it |
| `NoApproverFails` | Resolves to nobody under strategy `Error`: the approval would stop there, as `failure` says |
| `Undetermined` | Who approves it, or whether it passes on its own, depends on data only known once the approval runs |

Nothing is guessed. The preview runs no node: once the walk passes a script, a task or a subflow, the variables
that node writes are unknown — all of them for a task without an output variable and for an async task — and
the instance id is unknown from the start. A route whose conditions read an unknown variable is listed in
`undecided` and not followed; a step whose approvers come from a dynamic source, reached after such a node, is
`Undetermined`; with approver dedup on, so is a step whose approver may or may not have approved an earlier
step, one of several there who could pass it. An error the engine would fail on — a route matching no edge or
several, a condition that cannot be evaluated — ends the walk in `failure`.

Nothing is written: no instance, task, ledger row or notification, and no delegation's use is counted. The
preview is allowed to whoever can read the record with their own permissions
(`FlowInstanceAccessGuard.requireRecordReader`) and is never waived from row scope; taking part in the record's
earlier approvals is not enough. Users are named as in the approval history. It runs in no transaction of its
own, and `FlowClient.previewRecord` should be called outside one: a refusal thrown through a transactional
method marks the transaction it joins rollback-only.

Default flows reach tenants as tenant seed files: one `FlowDesign` row per file, its trigger naming the
model. When seed-starter announces the rows a file wrote (`SeedFileLoadedEvent`), `SeedFlowPublisher`
publishes each new design; a design that does not compile fails the file in that tenant. Tenants then edit
their own copy in the designer, and a run never brings the template back over it. A reapply of the file does,
on purpose, and the designs it rewrites are published again, so the instances started afterwards follow what
the design now says.

## Notifications

The default `ApprovalNotificationService` is `CompositeApprovalNotificationService`, which tells two channels,
each on its own — one failing neither silences the other nor touches the action:

- **Inbox** (`InboxApprovalNotificationService`), always: Workflow-type inbox messages through message-starter
  that point at the flow instance (`sourceModel` = `FlowInstance`, `sourceId` = its instance id; the client opens
  the instance and goes on from there). Titles longer than the inbox's 200 characters are cut; texts are
  translated into the tenant's default language. A task to act on and a CC are told here too, though My Tasks
  lists them and the client counts them in its header: the inbox message says one arrived.
- **Email** (`MailApprovalNotificationService`), on a flow whose `emailNotification` is not turned off: a mail
  through message-starter to each recipient's account email (`UserAccount.email`; a recipient without one is
  skipped), worded by the tenant's mail template of the notice's code. The template's variables are `subject`
  (the instance title, else the flow name), `content` (the comment or reason, when there is one), `flowName`,
  `recordCode` and `link` — the approval's page, `system.site-url` + `/approvals/{instanceId}`, empty when the
  site URL is not configured. flow-starter ships no mail templates: a tenant without a template of the code gets
  no email, and the failure is logged.

One mapping (`ApprovalNotices`) decides who is told what, on both channels:

| Event | Told | Inbox | Mail template |
|---|---|---|---|
| A step's approvers still to act are assigned; a task is transferred, delegated or reassigned to someone | The approvers still to act; the new holder | yes — "Pending approval" (also in My Tasks) | `flow.task-assigned` |
| A CC | Its recipients | yes — "For your information", with the CC's message (also in My Tasks) | — (not emailed) |
| An urge | The approvers urged | yes | `flow.urged` |
| A step past its deadline, under the `Remind` timeout strategy | Its approvers still to act | yes | — (not emailed) |
| Approved; rejected or returned (with the comment) | The initiator | yes | `flow.approved`, `flow.rejected`, `flow.returned` |
| Terminated, or a step reassigned, by an administrator (with the reason) | The initiator | yes | `flow.terminated`, `flow.reassigned` |
| A record's approval fails (with the error) | The initiator and the holders of the roles in `flow.notification.failure-role-codes` (default `TENANT_ADMIN`) | yes | `flow.failed` |

Only an Approval flow announces its completion: an Automation run is not something the user whose write triggered
it asked to hear about, and an automation run that fails is the operators' to watch. Messages are written after
the action commits, in a transaction of their own: a rolled-back action sends nothing, and a message that cannot
be written is logged without failing the action. A failure is told at once, since the failed attempt's own
transaction rolls back.

`InboxReadMarker` keeps the inbox current: once an approver acts on an approval — approves, rejects, returns,
transfers, delegates, adds a signer or reads a CC — their unread inbox messages about that instance (the pending
approval or the CC itself, an urge, a timeout reminder) are marked read after the action commits, so the bell
counts what is still news to them (an urge, a comment or a CC sent does not count as acting). My Tasks stays the
work list.

An application `ApprovalNotificationService` bean replaces the default, both channels with it; to add a channel —
SMS, IM — declare a `CompositeApprovalNotificationService` of your own that lists the inbox and email channels
with yours.

## Trigger And Messaging Integration

Supported trigger sources (`TriggerSource` sealed sub-types), each the start contract of the flows that
declare it (see [Flow Scenarios](#flow-scenarios) for which scenario takes which):

- `EntityChange` — a change log of the model, when the trigger's condition holds (see
  [A Flow's Start](#a-flows-start-its-trigger-and-the-model-it-names)). Each change log starts a run at most once per flow: its
  `uuid` travels as the event id onto `FlowInstance.triggerEventId` (unique with the flow), so a redelivered
  change log returns the run it already started, while distinct changes of one row each start their own
- `Cron` — the flow's own Quartz schedule; started only by it (see [Cron-triggered flows](#cron-triggered-flows)):
  other cron jobs' ticks never start it, and `POST /flow/automation/trigger` refuses the type
- `FieldChange` (COMPUTE scenario only)
- `Api` (AUTOMATION scenario only) — started explicitly by id; no event matches it, and
  `POST /flow/automation/trigger` refuses the type
- `Subflow` (AUTOMATION scenario only) — run only from a parent's Subflow node; refused as an event likewise
- `Submit` (APPROVAL scenario only) — started when a record of the document it names is submitted and its
  condition holds (see [A Flow's Start](#a-flows-start-its-trigger-and-the-model-it-names)); the document is the
  flow's, fixed when it is created. Started only by record submission (see Approving Business Records); change logs
  never produce it and `POST /flow/automation/trigger` refuses it

Pulsar-connected components are conditionally registered:

| Property | Component | Purpose |
|---|---|---|
| `mq.topics.flow-event.topic` | `FlowEventProducer`, `FlowEventConsumer` | Async trigger fire and consume |
| `mq.topics.flow-async-task.topic` | `FlowAsyncTaskProducer`, `FlowAsyncTaskConsumer` | Async task dispatch and callback resume |
| `mq.topics.change-log.topic` | `ChangeLogFlowConsumer` | Entity change driven flow triggers |
| `mq.topics.cron-task.topic` | `CronFlowJobProvider`, `CronTaskFlowConsumer` | Cron-triggered flows on the cron schedule, and their start |
| `mq.topics.flow-timer.topic` | `FlowTimerConsumer` | Timer wake-up consume side |

Important timer note:

- The module includes the timer resume consumer.
- A scheduler or producer that publishes `FlowTimerMessage` still has to be provided by the host application or another integration module.

### Cron-triggered flows

A Cron-triggered flow owns its schedule: the trigger's `cronExpression`, in the Quartz format cron-starter runs
(`second minute hour day-of-month month day-of-week [year]`, e.g. `0 0 9 ? * MON-FRI` for 09:00 on weekdays, in
the scheduler's time zone). Publishing parses it the way the scheduler does: a blank expression fails with
`CRON_MISSING_EXPRESSION`, one the scheduler cannot run — or that never fires again — with `CRON_INVALID_EXPRESSION`.
`GET /flow/cron/preview` shows the designer the same judgment beside the expression, with its meaning in words and
its next runs in the server's zone; a Timer node's cron is read the same way. Automation, Validation and
Compute flows may run on a schedule, Approval flows may not (see [Flow Scenarios](#flow-scenarios)); a scheduled
Validation or Compute flow is evaluated transiently, like any of their runs.

- **Scheduling.** `CronFlowJobProvider` is a cron-starter `CronJobProvider`: the scheduling leader reads every
  tenant's active Cron-triggered revisions (`FlowBundle.cronExpression`, copied from the trigger at publish) when it
  starts scheduling and again every 30 seconds, and runs one job per revision, named `flow:<bundleId>`, for the
  enabled flows only. Publishing, activating another revision (rollback or roll-forward), switching to another trigger
  and disabling or enabling the flow therefore move, add or remove the schedule within 30 seconds, whichever instance
  did it — an execution due in between is skipped, never run twice;
  no `SysCron` row is written. Nothing is scheduled while `mq.topics.cron-task.topic` is not configured.
- **Tenants.** A tenant's flow runs in that tenant's context, and only while the tenant is active; a platform-shared
  flow (`tenantId = 0`) runs once in every active tenant, each run in that tenant's context. Without multi-tenancy
  (`system.enable-multi-tenancy=false`), every flow runs once, with no tenant.
- **Starting.** `CronTaskFlowConsumer` starts the revision a `flow:<bundleId>` message names
  (`FlowAutomationService.fireCron`), and only while it is still its flow's active revision: the job of a replaced
  revision firing before the leader's next read starts nothing. Every other cron message — the maintenance crons,
  other modules' jobs, a `SysCron` row whatever its name — is ignored, and no trigger event matches a Cron flow. The
  flow starts with no initiator and the time the schedule fired as variable `triggerTime`, and an Automation flow's
  fire is logged in `FlowEvent` like any other.
- **Every execution starts its own run**, even while the previous one is still running or waiting. An execution
  carries no event id, so unlike a change log, a redelivered cron message is not recognized as one that already ran.

## Persistence Model

| Entity | Description |
|---|---|
| `FlowDesign` | Draft working copy |
| `FlowBundle` | Published compiled bundle |
| `FlowInstance` | Persisted runtime instance state (trace and approval history are NOT embedded — see the two dedicated tables), with what a record's approval recorded at submission: `subjectId`, `departmentId`, `effectiveDate`, `recordCode`, `superseded`. Approval instances are kept; finished Automation runs, with their trace, branches and the trigger event log, are purged after `flow.automation.retention-days` (default 30) by the `flow_automation_purge` cron |
| `FlowExecutionTrace` | Append-only execution trace rows (the in-memory state keeps only the current attempt's delta) |
| `FlowApprovalTask` | Pending/completed/CC task projections |
| `FlowApprovalRecord` | Approval action audit ledger — the authoritative approval history, flushed by the instance store in the same transaction as the instance row; `operatorId` names an administrator who acted on the actor's behalf |
| `FlowDelegation` | Delegation rules |
| `FlowEvent` | Trigger event logs |
| `FlowDebugHistory` | Debug snapshots |
| `FlowParallelBranch` | Parallel branch tracking |

Primary storage adapters in this module:

- `OrmFlowBundleRegistry`
- `OrmFlowInstanceStore`

`OrmFlowBundleRegistry` loads nothing at startup and caches per tenant:

- **Trigger matching** reads an index of the active bundles of the tenant's enabled flows, loaded in that tenant's own context on its first event (an empty result is kept too) and merged with the platform-shared bundles (`tenantId = 0`); a context without a tenant matches the shared ones only. Entries live for `flow.trigger-cache-ttl-seconds` (default `60`). Publishing, activating a revision, or disabling or enabling a flow drops the tenant's entry at once on the instance that did it; other instances pick the change up within the TTL. Change logs of models no flow watches are not turned into trigger events.
- **Definitions** are cached by `bundleId` in a bounded LRU (a published revision never changes). A bundle of another tenant reads as not found, whether cached or not; platform-shared bundles are visible to every tenant.
- **The active revision of a flow** is read from the database on every lookup — none while the flow is disabled — so starting by `flowId` follows a publish, rollback, disable or enable made on any instance.

## Node Descriptor Contract

The editor palette is built from `FlowNodeDescriptor` records exposed through `/flow/nodeDescriptors`.

- structural descriptors come from `BuiltinNodeDescriptorProvider`
- executor-backed task descriptors come from `TaskExecutorDescriptorProvider`
- descriptors include label, icon, sort order, config schema, default config, and allowed scenarios
- a config field's `type` names the editor's control; a `cron` field is a cron expression the editor previews through
  `GET /flow/cron/preview`
- what a setting holds decides its type, so the editor offers the right input: `template` for text interpolated as
  the node runs (`multiline` for a body), `expression` for AviatorScript, `stringList` for a list of texts (recipients,
  field names), `json` for a free JSON payload, `variableRef` for a variable's name, a picker for a record —
  `rolePicker` / `userPicker` / … , or `recordPicker` naming its `model` (a mail template) — storing `valueField`
  (`code`) instead of the id when given, and taking a typed value no record holds with `allowCustom`; a
  `keyValueMap`'s `valueType` says what its values are (`expression`, `template`)

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
| `Edge.data.condition` / `Edge.data.conditionExpression` (hoisted by the editor) | `FlowGraphEdge.condition` / `conditionExpression` |
| `Viewport {x, y, zoom}` | `FlowGraphViewport {x, y, zoom}` |

## Host Application Integration Checklist

Before enabling `flow-starter` in a real app, verify:

- add `flow-starter` to the application classpath
- create and expose `FlowDesign` drafts through the generic model APIs
- for approval emails: set `system.site-url` (the link in each email) and give each tenant the `flow.*` mail templates — or turn `emailNotification` off on the flows; provide your own `ApprovalNotificationService` for SMS or IM
- set `flow.notification.failure-role-codes` if failed approvals should reach roles other than `TENANT_ADMIN`
- check the organization follows the default HR conventions, or override `softa.flow.org.*`, or provide your own `OrganizationService`
- for business records: extend `ApprovalModel` (with an `employeeId`, or a `subject()` naming the employee field) and `ApprovalModelController`, implement a `ModelApprovalHandler<T>` per model — with `effectStatuses` when its approval takes effect later — and ship each default flow as a tenant seed file
- grant the monitoring console's endpoints and a data scope on `FlowInstance` to the administrators' roles (a department scope narrows by the subject's department)
- enable Pulsar topics if using triggers or async tasks
- provide timer scheduling/publishing if using `TIMER` nodes
- enable `CALL_SERVICE` explicitly (plus its bean-name allow-list) if flows need to invoke Spring beans; all other task node types ship working executors, conditionally registered on their capability beans
