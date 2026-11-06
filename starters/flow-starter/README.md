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
Automation alone.

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

Settings belong to one scenario too: `withdrawPolicy`, `returnEnabled`, `urgeEnabled`, `urgeIntervalMinutes`,
`remindAfterHours`, `approverDedup`, `selfApproval`, `emailNotification` and `cc` to Approval flows; `sync` / `rollbackOnFail` to Automation flows, and a sync flow — which runs inside the writer's
transaction — may not contain a Timer, AsyncTask or Approval node. The compiler rejects a flow that
breaks any of these rules.

| Approval setting | Values | Default |
|---|---|---|
| `withdrawPolicy` | Until when the initiator may withdraw: `Never`, `BeforeFirstAction` (until an approver first approves, rejects, returns, transfers or delegates — an automatic approval does not count), `UntilCompleted` | `Never` |
| `returnEnabled` | Whether the approvers may return the approval to the initiator — at any step, the same at every one: a return always goes to the initiator and ends the approval, and they edit the record and submit it again. A step has no switch of its own; one saved on a step before is ignored | off |
| `urgeEnabled` | Whether the initiator may urge the approvers the approval waits on; an administrator may either way | on |
| `urgeIntervalMinutes` | How often each waiting step may be urged, by the initiator or an administrator alike; at least 1 (`INVALID_URGE_INTERVAL` otherwise) | `240` |
| `remindAfterHours` | Hours after a step opens that its approvers still to act are reminded, then every as many hours, at most 3 times; at least 1 (`INVALID_REMIND_AFTER_HOURS` otherwise). Only a reminder — see [Overdue Reminders](#overdue-reminders) | none: no reminders |
| `approverDedup` | `Global`, `Contiguous`, `None` — see [Approver Resolution](#approver-resolution) | `Global` |
| `selfApproval` | `AutoApprove`, `RequireApproval` — see [Approving Business Records](#approving-business-records) | `AutoApprove` |
| `emailNotification` | Whether its notices are also emailed — see [Notifications](#notifications) | on |
| `cc` | Who is copied in as the approval is submitted, approved or rejected — see [CC](#cc) | none |

### Node Types

Current `FlowNodeType` values:

- Control: `Start`, `End`, `Timer`
- Routing: `InclusiveGateway`, `ParallelFork`, `ParallelJoin` (shown as "Join": it closes a fork, or an inclusive gateway's branches)
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

- **`Subflow` is a step like any other, run in one go**: the child must be an enabled Automation flow with a
  `Subflow` trigger, it must not call a flow already running above it, and it must not wait. Publishing refuses a
  `Timer` or an `AsyncTask` in a flow with a `Subflow` trigger (`SUBFLOW_WAITS`), and the designer offers as targets
  only the flows a Subflow node can call (`GET /flow/designs?subflowTargets=true`). What still goes wrong at run
  time — a target switched off, changed or calling back, a revision published before the rule stopping to wait —
  throws, and is handled as any failing step is: a submission refused, an approval kept with its instance failed
  ([When a step after an approval fails](#when-a-step-after-an-approval-fails)), an automation run failed. Model
  human steps in an Approval flow, not a child.
- **`ParallelFork` branches execute serially** (deterministic order), not
  wall-clock-parallel — the gateway models routing topology, not concurrency.
- **A `Timer` waits one way**: `durationSeconds` (at least 1), `cronExpression`, or `deadlineExpression` (an
  AviatorScript expression resolving to a time) — exactly one, else `INVALID_TIMER_CONFIG`. Its cron speaks a Cron
  trigger's dialect, the scheduler's Quartz (`FlowCron`), so a flow has one cron language: one the scheduler cannot
  run, or that never fires again, is `INVALID_TIMER_CRON`; a deadline that does not compile, `INVALID_TIMER_DEADLINE`.

The graph itself has exactly one `Start` — the entry; a node that merely lacks incoming edges is
not one (`NO_START_NODE` / `MULTIPLE_START_NODES`) — no cycle (`GRAPH_CYCLE`), every node reachable
from the `Start` along the edges (`UNREACHABLE_NODE`): an unreachable node would never run, and a
`ParallelJoin` it feeds counts its edge among the arrivals it waits for, so every instance would stop
there — and every node labelled (`MISSING_NODE_LABEL`, on the node's `label`): people see the label as the
step's name, in the approval center, the audit trail and a failed instance's message. A draft may break these
rules — saving never validates — but it cannot publish.

Diagnostics name what they are about as people see it on the canvas: a node by its label, an edge by its label or
else by the nodes it joins (`Manager → Finance`) — never by the generated ids, which the diagnostic carries in
`nodeId` / `edgeId` for the editor to anchor it. One about a connection — a condition, a default edge — carries its
`edgeId` and the `nodeId` it leaves. The trigger's diagnostics point at the Start node, where the designer edits the
trigger (`nodeId` of the single Start, `field` `trigger`; flow-level while the graph has no single Start). An
approval flow without an approval step is warned of at its Start (`NO_APPROVAL_STEP`, a `Warning`): a submission is
approved as it is made, which is allowed — End and every step are optional — but rarely meant. The engine's own
failure messages name a node the same way; only a revision published before labels were required may still show a
node's id.

### Routing: edge conditions

Conditions live on edges (`FlowGraphEdge`); a node carries none. Every node routes **exclusively** over its
outgoing edges, in order: it tries its conditional edges by their `priority` (1 first; edges without one after
those with one, in drawing order) and takes the first that holds — the conditions after it are not read — and,
when none holds, the one edge without a condition (the default, at most one per node, `MULTIPLE_DEFAULT_EDGES`).
The default is always tried last: a `priority` putting it before a conditional edge is refused
(`DEFAULT_EDGE_NOT_LAST`). The compiler lists each node's `outgoingEdgeIds` in that order; a revision compiled
before priorities lists them in drawing order, and two conditions holding there no longer fail the instance — the
one drawn first is taken. The exceptions: `InclusiveGateway`, which takes every conditional edge that holds, as
BPMN's does, and its default edge only when none holds (at most one default there too), and `ParallelFork`, which
takes all of them (a condition there is not read) — neither reads `priority`. A node that picks one of several
edges by their conditions has a default: publishing refuses one without (`NO_DEFAULT_EDGE`), so every request has
somewhere to go; a node whose only edge has a condition ends its branch when it does not hold. Nothing taken at a
node with several edges — an `InclusiveGateway` none of whose conditions holds, or a revision published before that
check — stops there with a message naming the step and asking for a default route (`FlowActionValidationException`):
a submission is refused (`400`); after an approval, the instance fails with it and the approval stands (see
[When a step after an approval fails](#when-a-step-after-an-approval-fails); zingkey-hcm#2382). `FlowGatewayRouter` decides; the approval path preview decides the same way. The designer
shows a node's order on its edges ("1", "2"… and "Other" on the default) and moves an edge up or down in it.

An `InclusiveGateway`'s branches either each end on their own, or all meet at one `ParallelJoin` — its paired join
(`BranchRegions.pairOf`): every branch, the default included, goes on to that join and enters it by one edge, never
ending or reaching another join first; branches share no node, and no edge from outside enters a branch or the
join. Otherwise `INCLUSIVE_JOIN_UNPAIRED`, saying which rule the graph breaks. The compiler names the paired join on
the gateway (`CompiledFlowNode.pairedJoinId`), and when the gateway routes, the engine counts the branches it did
not take as arrived there (`joinArrivalCounts`, `FlowExecutionOrchestrator.creditUntakenBranches`): the join waits
for the K branches taken and goes on once, after the last of them — "finance if over 1 000, travel if abroad, then
HR" asks HR once, after whichever ran. A rejection anywhere ends the whole approval, as always. Gateways and forks
nest, each with its own join. The approval path preview counts the same way.

Branches that may run at the same time — every edge of a `ParallelFork`, every conditional edge of an
`InclusiveGateway` — meet only at a `ParallelJoin` or an end (`End`, `ReturnValue`), else `CONCURRENT_BRANCHES_MEET`:
any other node they share runs once for each branch that reaches it, so HR is asked twice and everything after it
runs twice. A conditional edge and the default never run together, so an extra check that rejoins the default path
("finance if over 1 000, then HR") is fine, and a fork or a gateway with its join nested inside one branch is seen
through. A join
goes on by one edge (`JOIN_MULTIPLE_OUTGOING`): with more it stops waiting, and each branch goes on by itself. A fork's
branch that goes on to a join enters it by one edge on every path, never an end or another join instead
(`BRANCH_ENTERS_JOIN_TWICE`, `BRANCH_SKIPS_JOIN`): the join waits for as many arrivals as it has incoming edges, and
one that never comes lets the instance complete there.

Revisions published before these checks are stopped as they run (`FlowExecutionOrchestrator`). A node other than an
end entered a second time in one instance, or a join still short of branches when nothing is left to wait for, throws
a `FlowRuntimeException` naming the node, never completing the instance: a record's submission is refused and rolls
back, while after an approval — the approval kept — and in an automation start or a timer or async resume, the
instance fails, as any node failure does (see [When a step after an approval fails](#when-a-step-after-an-approval-fails)).

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
- `Failed` — a step could not run. It stays failed unless an administrator terminates it (→ `Cancelled`) or —
  when the approval could not go on after an approval, the approval kept — runs the steps after the approval again
  (→ `Running`, see [When a step after an approval fails](#when-a-step-after-an-approval-fails)); the submitter of
  a record's approval can instead take the record back (see [Approving Business Records](#approving-business-records))

## API Overview

Host applications embedding the engine in-process should integrate through the
`FlowClient` facade (`io.softa.starter.flow.api`) rather than looping back over
REST — actor / initiator / tenant identity is always resolved server-side from the
context, never passed as call parameters. `FlowAutoConfiguration` registers the default
`InProcessFlowClient` only when the application defines no `FlowClient` bean of its own.
See [docs/integration-guide.md](docs/integration-guide.md).

A refused request answers in the framework's envelope (HTTP 200, the code in the body) with its reason as the
message: an action the engine refuses (`FlowActionValidationException`) with `400`, a caller not allowed
(`FlowAuthorizationException`) with `403`, a flow that does not compile with `400` and its diagnostics — every one
of them, though only an `Error` refuses: a `Warning` is advice.
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
| `GET` | `/flow/designs?keyword=&scenario=&subflowTargets=&pageNumber=&pageSize=` | Paged draft list (canvas excluded); `subflowTargets=true` keeps the flows a Subflow node can call — the enabled Automation flows whose effective revision declares a Subflow trigger |
| `GET` | `/flow/designs/{id}` | Load one draft (canvas + optimistic-lock version) |
| `POST` | `/flow/designs/{id}/save` | Auto-save the canvas; stale versions are rejected |
| `POST` | `/flow/designs/{id}/delete` | Delete a flow that was never published; a published one is refused (disable it instead) |
| `POST` | `/flow/designs/{id}/duplicate` | Copy a flow under a fresh name (`"<name> (copy)"` when `newFlowName` is omitted) as an unpublished draft; an approval flow's copy keeps its document and submit condition, to be changed before it is published |
| `POST` | `/flow/designs/validate` | Validate the posted document; diagnostics returned as a success payload. `valid` is false only for an `Error`; a `Warning` (users the flow names who cannot act, see [Approver Resolution](#approver-resolution)) does not stop a publish |
| `POST` | `/flow/designs/{id}/publish` | Publish the saved draft as a new active revision; returns its `FlowBundleSummaryView` (`active` true, with the `changeDescription` sent) |
| `POST` | `/flow/designs/{id}/restore?bundleId=` | Restore the draft canvas from a historical bundle snapshot. A bundle that does not exist, has no design snapshot or belongs to another flow is refused (`400`) |
| `GET` | `/flow/designs/{id}/status` | Publish status: revision badge, dirty flag, `enabled` |
| `POST` | `/flow/designs/{id}/disable` | Switch the flow off: nothing starts it. Refused while any of its instances has not finished, or while a flow that may still run it calls it as a subflow. See [Enabling And Disabling A Flow](#enabling-and-disabling-a-flow) |
| `POST` | `/flow/designs/{id}/enable` | Switch the flow back on, on the revision that was effective |
| `GET` | `/flow/designs/{id}/availableVariables?nodeId=&afterNode=` | Variables visible to a node (expression autocomplete); `afterNode=true` — to the edges leaving it, its own outputs too |
| `POST` | `/flow/designs/{id}/preview` | An approval flow's test run: what submitting a request with these field values as this user would do under the saved draft — whether its submit condition holds, the route and each step's approvers — worked out without running anything. See [Testing a draft](#testing-a-draft) |

### Bundle APIs (published revisions)

Base path: `/flow/bundles`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/bundles?flowId=` | The revision history of one flow, newest first, read from its stored revisions (or, without `flowId`, the active bundle of every enabled flow of the caller's tenant) |
| `GET` | `/flow/bundles/{bundleId}?include=design` | One revision's summary, optionally with the canvas-complete design snapshot |
| `POST` | `/flow/bundles/{bundleId}/activate` | Make this revision the flow's effective one (rollback = roll-forward) |

Each returns `FlowBundleSummaryView`: `bundleId`, `flowId`, `flowName`, `revision`, `scenario`, `triggerType`,
`sync`, `rollbackOnFail`, `compiledAt`, `publishedAt`, `nodeCount`, `transitionCount`, `capabilitySummary`, and from
the stored revision `active` — whether it is the flow's effective one (one per flow) — and `changeDescription`, as
written when it was published. The list without `flowId` is read from the compiled revisions and leaves those two
out.

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
| `POST` | `/flow/instances/cc` | CC one or more people (`targetActorIds`) on the caller's pending step. See [CC](#cc) |
| `POST` | `/flow/instances/cc/read` | Mark a CC as read |
| `POST` | `/flow/instances/return` | Return a pending approval to the initiator, ending the instance as `Returned`. Only the step's approver, on a flow that allows returns (`returnEnabled`), with a comment saying what to change |
| `POST` | `/flow/instances/withdraw` | Withdraw an Approval instance: its initiator, while it waits on its approvers and the flow's `withdrawPolicy` permits it (`WithdrawPolicyGuard`). On a record's approval that failed, its submitter takes the record back whatever the policy, while the record still waits on it — as `withdrawRecord` does: a draft again, the failed instance kept |
| `GET` | `/flow/instances/{instanceId}/withdraw` | Whether the caller may withdraw the instance now — `true` exactly when a withdrawal would be accepted, by the same `WithdrawPolicyGuard`. Allowed to whoever may view the instance |
| `POST` | `/flow/instances/urge` | Remind the approvers the approval waits on — its initiator only, on a flow whose `urgeEnabled` is not turned off. Each waiting step is urged at most once per the flow's `urgeIntervalMinutes` (default 240), by the initiator or an administrator alike; each step urged gets its own `URGE` history entry, and when every step is still inside the interval the urge is refused. Returns `FlowUrgeStatus`, not the instance |
| `GET` | `/flow/instances/{instanceId}/urge` | Whether the caller may urge the approval now, and if not, when (`FlowUrgeStatus`) — never, for the initiator of a flow that turns `urgeEnabled` off. Allowed to whoever may view the instance |
| `POST` | `/flow/instances/comment` | Add a comment audit entry while the instance runs — its participants only: the initiator and the people it gives or gave a task to, by the same rule as viewing it (an approver whose step another's vote carried still comments; an administrator who urged it does not). An instance that has ended takes no more comments. Returns `true`, not the instance |
| `GET` | `/flow/instances/{instanceId}/nodes/{nodeId}/formPermissions` | Get field-level form permissions |
| `GET` | `/flow/instances/by-record?modelName=&rowId=` | A business record's approval history: its approval instances newest first, with the steps still waiting, the approvers yet to act and the approval actions, every user named (names are resolved only once the caller is allowed); a failed instance says where and why it stopped (`failure`). Each says whether the caller may open it (`viewable`) — its detail, `/summary`, `/overlay` and `/trace`, which the instance guard decides: every one for whoever can read the record, else the ones they take part in or whose `FlowInstance` row their roles read. An approver of an earlier submission sees the one in progress listed but not inside it, so a page keyed by the record opens an instance only when it is `viewable`. Allowed to whoever took part in its approvals or can read the record |
| `GET` | `/flow/instances/preview?modelName=&rowId=` | What submitting the record would do now, without submitting it: the Submit flow it would get and its approval steps, each with the approvers who would be asked or why it passes on its own; a refusal, no applicable flow or several are reported, a route decided only once the approval runs is reported as undecided. For a record in approval, the rest of its path (`InProgress`): the step it waits on now, then those after it. Nothing is started or written. Allowed to whoever can read the record (see [Previewing a record's approval](#previewing-a-records-approval)) |
| `GET` | `/flow/instances/{instanceId}/record` | The business record a record's approval runs on, as `{ record, fields }` — any other instance is refused, since its record was named freely rather than submitted. Its initiator and the people it gives or gave a task to read it past their own row permissions, field by field as the approval's steps show it, one-to-many rows included; anyone else who can read the record with their own permissions gets the model's ordinary read of it. `fields` holds the definitions of the fields shown that the caller's own metadata leaves out. See [The record an approval runs on](#the-record-an-approval-runs-on) |
| `GET` | `/flow/instances/by-record/record?modelName=&rowId=` | The business record, as `.../{instanceId}/record` returns it (`{ record, fields }`), whichever of its approvals is current — for a page keyed by the record that shows them all as its history. The newest approval the caller took part in lends it as that instance would; anyone else who can read the record gets the model's ordinary read, with no `fields` — the only read here of a record never submitted; everyone else is refused. See [The record an approval runs on](#the-record-an-approval-runs-on) |
| `GET` | `/flow/instances/{instanceId}/summary` | The instance as a row of `POST /flow/instances/search` shows it (`FlowInstanceSummaryView`, composed the same way): the steps it waits on with the approvers still to act, what a record's approval kept of its record, `awaitingRevision`, `recordDeleted` / `recordCancelled`, `effectStatus`, every user and department named; on the caller's own approval waiting on its approvers, `withdrawable` and `urge`, and on their own record's approval that failed, `withdrawable` (taking the record back). Allowed to whoever may view the instance |
| `GET` | `/flow/instances/{instanceId}/design` | The revision the instance runs on, exactly as `GET /flow/bundles/{bundleId}?include=design` returns it (`FlowBundleDetailView`: its `summary` and the `design` snapshot taken at publish) — to draw the instance's diagram. Allowed to whoever may view the instance: the bundle is read past their permissions on `FlowBundle`, which an approver seldom holds |

An action's `comment` is at most 256 characters, as a batch action's comment and an administrator's reason are; a
longer one is refused (`400`) before anything runs. An action on a step that no longer waits — handled by someone
else, or on another page, since the caller's was loaded — is refused (`400`, "This step no longer waits for
approval…") rather than failed as an error, so the page refreshes and shows where the approval stands
(zingkey-hcm#2386).

Who may view an instance: by default its participants — its initiator and the people it gives or gave a task
to: approvers pending or done, CC recipients, transfer and delegation targets, and the approvers a step
closed without them (the pending approvers a termination cleared, the other approvers of a step someone else's
vote carried). The instance's state and approval history name most of them (`FlowParticipants`); the rest are
named only by their task, which stays when it closes, so the access guard asks the instance's `FlowApprovalTask`
rows — CC tasks included, whatever their status — when the state and history name nobody, in one query however
many instances it asks about (`FlowInstanceAccessGuard.isParticipant`). An administrator's actions make nobody a
participant: urging or terminating an instance, or reassigning one of its tasks (recorded as the approver's
`TRANSFER` with the administrator as `operatorId`), gives them no task. Everyone else, administrators included,
goes through the standard role and data-scope configuration — they may read the `FlowInstance` row with their own
permissions, or, for a record's approval, read the record itself. The same participants comment on an instance
while it runs, read its record past their own row permissions and see their record's approval history. Reading the
record through the instance follows narrower rules: see [The record an approval runs on](#the-record-an-approval-runs-on).

The defaults need no grant on the flow models, which no business role holds. The engine's own bookkeeping —
loading and saving instances, the approval ledger, the task and branch projections, delegation and auto-CC
rules — runs past the caller's data permissions: who may act is the action handlers' call, who may see the
access guard's. The current user's own lists — inbox counts, the task search and the inbox
(`/flow/approvalTasks`), their approval history and sent CCs (`/flow/approvalRecords`), their delegations,
and `POST /flow/instances/search`, with their `FlowClient` counterparts — carry `@SkipPermissionCheck`:
pinned to the caller, row scope could only empty them; the filters a client adds to them are ANDed onto the pin,
never in its place (see [Filtering and sorting a list](#filtering-and-sorting-a-list)). The per-instance reads
(`.../instance/{instanceId}`, the instance detail endpoints) are not waived, because the guard judges everyone but
the initiator and the task holders by their own permissions.

### The record an approval runs on

The approval page (`/home/approvals/{instanceId}`, the link of every approval email) is opened by approvers who
may hold neither the menu of the record's model nor any permission on the flow models, so it reads everything
through the instance: `GET /flow/instances/{instanceId}` and `.../summary` for the instance, `.../design` for its
diagram, `.../record` for the record. The instance comes without the record's values — `inputPayload`,
`variables` and `returnData` are left out of it, and out of every approval action's answer (approve, reject, return,
transfer, delegate, CC, CC read, withdraw): the variables hold the record's fields as submitted, past the
field permissions below, so a field a step hid could be read off the instance (zingkey-hcm#2402). The record is read
through `.../record` alone. Only a record's approval lends its record
(`FlowInstanceAccessGuard.requireRecordAccess`):

- **Its participants — its initiator and the people it gives or gave a task to** read it past their own row
  permissions. They see every field, dynamic ones included, but the sensitive ones they may see on no row
  (`PermissionService.getHiddenSensitiveFields`); the field permissions (`formPermissions`) of the waiting steps
  they approve are laid over that — what a step lists as `Readonly` / `Editable` / `Required` shows, sensitive or
  not, what it marks `Hidden` does not, and with several steps a field any of them shows is shown. The initiator
  sees the default; anyone else with no waiting step — a CC recipient, an earlier approver, an approver a
  termination cleared — also misses what any approval step hides.
- **A one-to-many's rows follow the same rule on their own model**: each row loses the sensitive fields of its
  model the caller may see on no row, unless a waiting step of the caller lists the one-to-many itself as
  `Readonly`, `Editable` or `Required` — that grants its rows whole. A step marking the one-to-many `Hidden` leaves
  it out. The initiator and everyone else get the rows without the fields their own roles withhold. So a step
  that shows a salary adjustment's amounts to approvers who hold no salary set lists its lines:
  `"formPermissions": {"salaryAdjustmentDetailIds": "Readonly"}`; without it, they see the lines without them.
- **Anyone else who can read the record with their own permissions** — an administrator following a link — gets
  the model's ordinary permission-checked read of the same fields: their row scope, field blocks and masking, on
  the rows of each one-to-many too. Reading the `FlowInstance` row is not enough; everyone else is refused (`403`).

A page keyed by the record, which shows each of its approvals as its history, cannot read the record through one
of them: `GET /flow/instances/by-record/record?modelName=&rowId=` returns the same `{ record, fields }` whichever
approval is current (`FlowInstanceAccessGuard.requireRecordAccess(modelName, rowId, requesterId)`). The record's
approvals are walked newest first, read past the caller's permissions; the first the caller took part in — as its
initiator or a person it gives or gave a task to, a terminated approval's cleared approver by their task (the tasks
of all the approvals newer than the first one whose state and history name the caller are asked in one query) —
lends the record as `.../record` of that instance would; the record's approval history (`.../by-record`) admits the
same people. An approver it waits on sees the record as their step shows it; someone who took part only in an
earlier submission sees what that approval shows a participant with no waiting step — the default, without what an
approval step of its revision hides. Anyone else who can read the record with their own permissions gets the
model's ordinary read, with empty `fields` — the only way a record never submitted, a draft its owner opens, is read
here; everyone else is refused (`403`). A model that does not exist is `404`, and so is a record deleted under
someone who took part in its approvals; to anyone else a missing record is refused like one they cannot read, so the
endpoint never tells them whether it exists.

The response is `{ record, fields }` (`FlowInstanceRecordView`). `record` is read as the model read API returns a
record: relations and options as references, each one-to-many with its rows, a field with a `maskingType` masked.
`@DataMask` walks no response — it turns masking on for the request and the read pipeline masks each row as it
reads it — so the wrapper changes nothing. `fields` holds, by model name — the record's model, and the model of a
one-to-many's rows — the definitions of the fields `record` shows that the caller's own metadata leaves out: the
sensitive fields a step grants, which `GET /metadata/getMetaModel` withholds from them. Each is a `MetaFieldDTO`,
as the metadata API serves a field, for the client to merge into the models it loaded. It is empty for a reader of
the record and whenever nothing withheld is shown. The definitions come from metadata-starter's own mapping
(`MetadataFieldDefinitions`), an optional dependency; an application without metadata-starter serves no metadata to
merge them into, and gets none.

```json
{
  "record": {
    "id": "7",
    "code": "SA-0007",
    "employeeId": {"id": "42", "displayName": "Alice Tan"},
    "salaryAdjustmentDetailIds": [
      {"id": "71", "salaryProfileItemId": {"id": "3", "displayName": "Basic Salary"},
       "oldAmount": "5000.00", "newAmount": "5500.00"}
    ]
  },
  "fields": {
    "SalaryAdjustmentDetail": [
      {"fieldName": "newAmount", "modelName": "SalaryAdjustmentDetail", "fieldType": "BigDecimal", "label": "New Amount", "scale": 2},
      {"fieldName": "oldAmount", "modelName": "SalaryAdjustmentDetail", "fieldType": "BigDecimal", "label": "Old Amount", "scale": 2}
    ]
  }
}
```

(A definition carries every `MetaFieldDTO` attribute; the example shows a few.)

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
| `filters` / `orders` | Conditions and a sort on the stored fields of `FlowInstance`, as a metadata-driven table sends them; `orders` replaces newest first. See [Filtering and sorting a list](#filtering-and-sorting-a-list) |

A row (`FlowInstanceSummaryView`) leaves out the heavy state columns and the trace — fetch the instance or its
overlay for those. It carries the flow, title, initiator, status and the steps the instance waits on now
(`currentSteps`, each with the approvers still to act), every user named, and for a record's approval what its
submission recorded (*Approving Business Records*): `subject`, `subjectDept`, `effectiveDate`, `recordCode`, `summary`,
and `awaitingRevision`. On the caller's own approvals that wait on their approvers, `withdrawable` and `urge` say
whether they may withdraw or urge them now, and on their own records' approvals that failed, `withdrawable` whether
they may take the record back; on the monitor search, `urge` says whether the caller may urge as an
administrator — the flow's `urgeIntervalMinutes` decides, whatever its `urgeEnabled` says. `recordDeleted` marks an
approval whose record is gone — a draft deleted after a return or a withdrawal — `recordCancelled` one whose record
was cancelled after it ended, and `effectStatus` says where an approved record's change stands, for a model whose
approval takes effect later (`ModelApprovalHandler.effectStatuses`).

### Filtering and sorting a list

The approval center's lists — the task boxes of `POST /flow/approvalTasks/search` and both instance searches — take,
besides what each names itself, `filters` and `orders` in the grammar `Filters` and `Orders` parse, as a
metadata-driven table over `FlowApprovalTask` or `FlowInstance` sends them:
`[["instanceTitle", "CONTAINS", "taxi"], "OR", ["recordCode", "CONTAINS", "taxi"]]`, nested at will, and
`[["submittedTime", "DESC"]]`. The rows still come from these endpoints, not the generic `/{model}/searchPage`:
they pin the rows to the caller and add the names the models do not hold (`FlowUserNames`, `OrganizationService`).

- **ANDed on, never in place.** The filters narrow what the list returns anyway — the caller's pin (their own
  tasks, their own record approvals), the box, the typed filters, and on the monitor search the caller's data
  scope — as a subtree of their own, so an `OR` in them cannot reach past it: a filter naming another actor finds
  nothing.
- **Stored fields only.** They may name only fields the model stores. A dynamic field (`flowName`, `scenario`), a
  path into another model (`flowId.flowName`), a name the model does not have, or such a field compared against
  (`{{ @field }}`) is refused (`400`) naming the field, before anything is read (`CallerConditions`). A filter on
  `searchName` — the toolbar search — stands for the model's `@Model(searchName)`: `instanceTitle` and `recordCode`
  on `FlowApprovalTask`, `title` and `recordCode` on `FlowInstance`.
- **Orders replace the list's default** and end on `id`, in the direction of their first, unless they sort by it —
  rows tied on what was asked keep one order from page to page.

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
| `GET` | `/flow/approvalTasks/counts` | The top bar's counts for the current actor in one request (`{pendingApprovals, unreadCc, unreadNotifications}`): the `Pending` box of `/search` and the unread CC tasks, counted, and the unread inbox notifications as `GET /InboxNotification/myCountUnread` counts them (`0` without message-starter's inbox). The bell's badge shows `unreadNotifications` alone — each new task comes with a notification, so adding the tasks would count it twice — and its Tasks tab shows the task counts |
| `POST` | `/flow/approvalTasks/search` | One page of the current actor's own tasks in a box: `{box, filters?, orders?, pageNumber?, pageSize?}` (`FlowApprovalTaskSearchRequest`; pages default to 1 and 50) → `Page<FlowApprovalTaskView>`. `box` is `Pending` — the approval tasks waiting on them, newest first; `Handled` — the approval tasks that closed, whatever closed them, latest closed first; or `Cc` — their CC tasks, read and unread, newest first. `filters` / `orders` on stored fields of `FlowApprovalTask`: see [Filtering and sorting a list](#filtering-and-sorting-a-list) |
| `GET` | `/flow/approvalTasks/inbox?flowId=&instanceId=&nodeId=&includeCompletedApprovals=&pageNumber=&pageSize=` | Unified inbox view (pending approvals + CC; optional completed approvals) |
| `GET` | `/flow/approvalTasks/instance/{instanceId}` | All tasks for one runtime instance (participants / initiator only) |

Each task (`FlowApprovalTaskView`) carries its holder's name (`actorName`), the names of every account it names (`actorNames`, by id: its candidates, who approved, rejected, closed or blocks it) and comes with its instance's context —
`instanceTitle`, `modelName` / `rowId`, `instanceStatus`, the `initiator` and `submittedTime`, and what a record's
approval recorded of its record (`subject`, `subjectDept`, `effectiveDate`, `recordCode`, `summary`), every user,
employee and department named — where a request still in approval stands now (`currentSteps`: the steps its instance
waits on, with the approvers still to act, named; one query per page, `CurrentStepsReader`, shared with the instance
lists), whether a later submission of the record replaced it (`superseded`), when the step is due
(`dueTime`, the flow's `remindAfterHours` after it opened, when the flow sets them — see
[Overdue Reminders](#overdue-reminders)) — and with what its step's configuration says about
acting on it, read from the revision the
instance runs on: `returnAllowed` (the flow's `returnEnabled`), `fillIn` (the step makes a field required: the
approver fills it in and submits, rather than just approving) and `batchable` (open to its approver now — pending,
the instance waiting — on a step that is not `fillIn`). See [Batch actions](#batch-actions).

The tasks are a projection of the approval history, brought up to date on every change of the instance:

- an approval task per approver and turn. It closes with what its holder did — `Approved`, `Rejected`, `Returned`,
  `Transferred`, `Delegated` — even when the same action went on to record more (the next step approving itself, a
  CC as the approval ended); a task its holder did not act on closes `Withdrawn` after a withdrawal, else
  `Canceled` (another approver ended the step, an administrator terminated the approval). Closed, it stays as it
  is: the others' later votes do not copy it;
- a CC task per recipient, step and cycle: `Pending` until they read it, then `Read`, ending when they read it.

A task, open or closed, keeps its holder a participant of the instance (see [Instance APIs](#instance-apis)): the
approver a termination cleared still opens the approval from their task email.

A task also stores its instance's list fields, so the boxes filter and sort by them in the database:
`instanceTitle`, `recordCode`, `modelName`, `rowId`, `initiatorId`, `submittedTime` (the instance's `createdTime`),
`subjectId`, `departmentId`, `effectiveDate` — plain values, as `FlowInstance` holds them. The sync copies them from
the instance's row when it writes a task, and an instance's never change once it starts; a view shows the task's own
copy only when the instance row is gone, its instance's values otherwise.

> **Upgrading**: the scanner adds the nine columns to `flow_approval_task` when the new jar boots; rows written
> before are filled from their instance by a SQL the deploying application ships, run after the new jar has started
> (a row still open is also filled the next time the sync updates it). It sets the nine columns from `flow_instance`
> — `title` → `instance_title`, `created_time` → `submitted_time`, the rest by the same name — joined on `tenant_id`
> and `instance_id`, only on rows whose `submitted_time` is still null, so a second run changes nothing; a task whose
> instance was purged keeps nulls. Until it has run, the boxes still list every task and show its instance's values,
> but a filter on those fields misses the unfilled rows and a sort on them puts them at one end. On an environment
> whose scanner applies nothing (an empty `scanner-scope`), the application adds the columns and their catalog rows
> first, as for any annotation change there.

### Approval Records

Base path: `/flow/approvalRecords`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/approvalRecords/instance/{instanceId}` | Approval history by runtime instance |
| `GET` | `/flow/approvalRecords/history` | Actor-scoped approval history |
| `GET` | `/flow/approvalRecords/cc/sent` | Sender-side CC history |

An entry (`FlowApprovalRecord`, `FlowApprovalRecordView`) records its `action` (`ApprovalActionType`: `APPROVE`,
`AUTO_APPROVE`, `REJECT`, `TRANSFER`, `DELEGATE`, `ADD_SIGN`, `CC`, `READ`, `RETURN`, `WITHDRAW`, `URGE`, `COMMENT`,
`TERMINATE`, `RETRY`; `ADD_SIGN` appears only in history recorded before the add-sign actions were removed), its
`actorId` and, for an action done on the actor's behalf, `operatorId` — the administrator who reassigned the actor's
task (a `TRANSFER` entry from the approver to the new one); empty when the actor acted themselves. An instance's history
(`/instance/{instanceId}`) names its people: `actorName`, and `targetActorName` for the one an entry hands to (the CC
recipient, the transfer or delegation target). A record's history (`/flow/instances/by-record`) gives each action its
`operator` — the administrator, named.

### CC

An approval flow's CC rules are part of its definition (`cc`, a list of `FlowCcRule`), set in the flow's settings
and published with each revision — an instance follows the rules of the revision it runs on. A rule says when:

- `OnSubmit`: as the approval is submitted.
- `OnApproved`: as it ends approved.
- `OnRejected`: as it ends rejected — at the first rejection.

and who: `recipientSource`, one group in the shape of each of an approval step's `approverSources` — specific people
(`{"type": "Users", "userIds": [...]}`), or a source that finds them: the subject's supervisor, a role, a department's
members…; found the way a step's approvers are (`ApproverResolutionService.resolvePeople`), so a recipient whose account cannot act is left
out (see [Approver Resolution](#approver-resolution)). Several groups at one timing are several rules. CC is the
approval's, not a step's: a step that should involve someone else has them as its approvers.

An automatic CC is recorded like a manual one: one `CC` entry per recipient in the approval history. The entry
has no actor, since no person sent it. It sits on the start node (`OnSubmit`, and `OnApproved` when every step
passed on its own) or on the step whose action ended the approval, in that node's current cycle. So the
recipient:

- has a CC task in the inbox, which they mark read, and an inbox notice;
- is a participant of the instance: they open it and comment on it.

A recipient already CC'd on the same node and cycle, by a rule or by hand, is not CC'd again.

An approver of the pending step, still to vote, CCs people by hand with `POST /flow/instances/cc`: one or more
(`targetActorIds`) in one action, with an optional comment. The people who already see the approval from the step
are skipped — its approvers, the actor among them, and anyone CC'd on it this cycle — and the rest get one `CC` entry
each, with the approver as its actor, and one inbox notice together. The action is refused when nobody is left, and
as a whole when anyone chosen cannot act, naming each of them and why ("Ann Lee's account is frozen").

### Delegation

Base path: `/flow/delegations`

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/delegations` | Create a delegation rule |
| `GET` | `/flow/delegations/my` | Delegations created by the caller (delegator resolved from the login context) |
| `GET` | `/flow/delegations/to-me` | Active delegations assigned to the caller (delegate resolved from the login context) |
| `GET` | `/flow/delegations/flows` | The flows a rule can be scoped to: every enabled Approval flow of the caller's tenant with an effective revision, sorted by name, each with the approval steps of that revision — `[{flowId, flowName, nodes: [{nodeId, label}]}]` (`FlowDelegationFlowView`). Read past the caller's row scope, so an employee sees them all |
| `POST` | `/flow/delegations/{id}/cancel` | Cancel one of the caller's delegation rules; another user's is refused (403) |

A rule is in force (`active`) from its creation until it is cancelled, or, with `autoExpire`, until its end time
passes; between those, only the tasks that arrive within its period (`startTime` / `endTime`, either open) go to the
delegate — tasks already waiting stay where they are. It needs the approver and the delegate, two different people,
a delegate whose account can act (see [Approver Resolution](#approver-resolution)), a period that ends after it
starts when both its ends are given, and, for scope `Flow` or `Node`, a flow — and step — that `GET /flow/delegations/flows`
lists; otherwise it is refused (`400`), whoever creates it. The lists return `FlowDelegationView`: the rule with
`delegatorName` and `delegateName`.

A rule whose delegate cannot act when a task arrives — their account was frozen since — does not apply: the task
stays with the approver (in a chain, with the last delegate who can act), the instance's trace says why, and nobody
is told. The approval path preview follows the same rule.

### Directory

Base path: `/flow/directory` — what the people and department pickers list: who to transfer to, CC or delegate to,
whose requests to filter by, and the people and departments a flow names. The application grants these
endpoints in the permissions of the pages whose pickers read them — approving, managing one's delegations, the
designer, the monitor — and does not list `/flow/directory/**` in `permission.authenticated-bypass-patterns`: there the
endpoint gate is skipped, and a consultant's expiry with it, so anyone signed in could list everyone. It shows a name,
an employee code, a department and a position, and only of people with a login account.

A person's search (`/people`) reads the directory model, `softa.flow.org.directory-model`, with the caller's own row
scope: whom a role's holders may pick is that role's scope on the directory model. The employee model's own scope is
the wrong one — an employee's would leave them only themselves to pick, and opening the employee model to them would
open every HR field with it — so an application points the directory at a read-only projection of its employees that
holds only what a directory shows (zingkey-hcm's `EmployeeDirectory`) and gives every role a scope on it, "all" by
default, narrowed where a role should see fewer. Unset, the directory model is the employee model, its scope included.
It offers only people who can act, and says nothing of who cannot (`active` is null). The designer and the operations
console run flows rather than take part in them: `/people/all` lists every account for them — people who can no longer
act on request, and accounts without an employee record, a tenant's administrators — each saying whether it can act,
past the caller's row scope, and only their permissions should list it. Names of people and departments already picked,
and the department search, are read past the caller's row scope. Finding approvers by rule never reads the directory
model: a supervisor or a department leader is found whatever the submitter may see.
It reads through `OrganizationService` (`searchPeople`, `searchAllPeople`, `getPeople`, `searchDepartments`,
`getDepartments` — empty unless the application's service implements them; `FlowOrgService` does), the tenant always
applying.

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/directory/people` | People whose login account signs in (see [Approver Resolution](#approver-resolution)) matching `keyword` on the directory model's search fields (code or name), all when blank, by name, as the caller's row scope on the directory model lets them read it: `DirectoryPerson` — `id` (the account id an approver, a CC recipient or a delegate is stored as), `name`, `code`, `departmentName`, `positionName`; `active` is null. `limit` defaults to 20, at most 200: a picker shows more by asking again for the first 40, 60 and so on as its list is scrolled, never for a next page — `/people/all` orders employees and accounts together by name in memory, which a database's collation need not agree with, so a page read on its own could repeat or skip someone. Two people of one name are ordered by id, so asking again keeps them in place |
| `GET` | `/flow/directory/people/by-ids` | The people `ids` names (comma-separated, at most 200), in that order, whatever the caller's row scope: what names a picked value once the search has moved on. An account without an employee record — a tenant administrator — comes back with its name alone, as the approval views name it (nickname, else username); an id naming no account is left out. Whether they can act is not said |
| `GET` | `/flow/directory/people/all` | For the designer and the operations console: every login account matching `keyword` — people on the directory model's search fields, accounts without an employee record on the account model's — by name, each with `active`; only those that can act unless `includeInactive=true`, as when moving work off someone who left. Past the caller's row scope; `limit` as above |
| `GET` | `/flow/directory/people/all/by-ids` | `/people/by-ids` with `active`, for the designer and the operations console |
| `GET` | `/flow/directory/departments` | Open departments matching `keyword` on the department model's search fields (code or name), by name: `DirectoryDepartment` — `id`, `name`, `code`, `parentName` (names repeat between branches). `limit` as above |
| `GET` | `/flow/directory/departments/by-ids` | The departments `ids` names, closed ones included, in that order; at most 200 |

Accounts without an employee record are not searched: what everyone picks from is their colleagues. A picker used
only by administrators that must offer them too — the designer's specific people — adds them from the account
model with the caller's own permissions.

### Monitor

Base path: `/flow/monitor` — the administrators' console. No role is built in: each endpoint is granted like any
other, and what the console reads or acts on is the caller's own permissions on `FlowInstance` — their roles' grant
and data scope; taking part in an instance is not enough. A department scope (`DEPT_SUBTREE`,
`MANAGED_DEPARTMENTS`) narrows by `FlowInstance.departmentId`, the subject's department at submission — a plain
department-id column the permission-starter scopes filter by the ids of the departments in range — so a role
scoped to the departments it manages sees and administers their approvals; an automation run belongs to no
department. Opening another user's instance (detail / overlay / trace / approval history) follows the viewing rule
under [Instance APIs](#instance-apis), as for any viewer who is not a participant — and what the console does to an
instance (urge, terminate, retry, reassign) makes no administrator one.

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/monitor/health` | Flow runtime health snapshot (per-status instance counts, overdue timers) |
| `POST` | `/flow/monitor/instances/search` | Cross-initiator paged instance summaries, read with the caller's own permissions: the `FlowInstance` rows their data scope reaches. Honors the request's `initiatorId` filter when present; otherwise as [Instance search](#instance-search) |
| `POST` | `/flow/monitor/instances/urge` | Urge an approval as an administrator rather than its initiator: whoever may read the instance's `FlowInstance` row with their own permissions, whether or not the flow's `urgeEnabled` lets its initiator urge. The flow's `urgeIntervalMinutes` applies — a step urged within it, by anyone, is not urged again. Returns `FlowUrgeStatus` |
| `POST` | `/flow/monitor/instances/terminate` | End each instance named (`instanceIds`, at most 100) that is running, waiting or failed, with one `reason` (required): its pending approvals close, it ends `Cancelled` — a record's approval cancels its record — and the history records a `TERMINATE` entry with the reason, which the initiator is sent. Only instances the caller's permissions read; each in its own transaction. Returns `FlowBatchActionResult` |
| `POST` | `/flow/monitor/instances/retry` | Run again the steps after an approval of each instance named (`instanceIds`, at most 100; an optional `comment`) that failed in them — see [When a step after an approval fails](#when-a-step-after-an-approval-fails): the approval goes on from the step it failed after, and the history records a `RETRY` entry by the caller; should a step fail again, the instance fails again. Refused for an instance that failed otherwise, and for a record taken back by its submitter or submitted again since. Granted with terminating — whoever may end a failed approval may carry it on. Only instances the caller's permissions read; each in its own transaction. Returns `FlowBatchActionResult`: an instance run again and failed again is `Failed`, with the step and the error |
| `POST` | `/flow/monitor/approvers/reassign/preview` | How many of an approver's pending approval tasks (`fromActorId` → `toActorId`), on the instances the caller's permissions read, a reassignment would move: `FlowReassignPreview` (`tasks`, `instances`). A new approver who cannot act is refused (`400`), naming them |
| `POST` | `/flow/monitor/approvers/reassign` | Move each of those tasks to the new approver, with a `reason` (required), each in its own transaction: recorded as the approver's `TRANSFER` with the caller as `operatorId`; the new approver is given the task, the initiator is told who now holds it. Only the steps the instances wait on now move — a step not reached yet resolves its approvers when reached; CC tasks stay. A new approver who cannot act is refused as a whole (`400`) before any task moves. Returns `FlowBatchActionResult` |
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

### Approval Threshold

- Approval modes (`config.approvalMode`): `AnyOne` (default), `Unanimous`, `MinCount`, `Percentage` — how many
  approvals pass the step.
- A rejection has no threshold: **one approver's rejection rejects the approval**, however many approvals the step
  counts — an all-approve step (会签) and an any-one step (或签) alike, as mainstream approval products have it. An
  approver who already approved the step cannot then reject it. A step has no reject mode; a `rejectMode`,
  `rejectMinCount` or `rejectPercentage` saved on a step before is ignored.
- A config holds these by their JSON values, as above; the enum constant names (`ANY_ONE`) are not read.
- One actor can only cast one effective vote per cycle.
- Compile-time validators reject an impossible approval threshold (`INVALID_MIN_COUNT`, `INVALID_PERCENTAGE`).
- The approval mode counts over the step's pool — everyone its groups find, each once (see
  [Approver Resolution](#approver-resolution)): any one of them, all of them, so many, such a share. It applies only
  to a step that may have several approvers (`ApprovalNodeConfig.mayHaveSeveralApprovers`): several groups, or one
  naming several people or finding a source that may find several. A step whose one group names one person, or is a
  supervisor, a department's leader or HR business partner (`ApproverSourceType.isFindsOne`: one person each — one
  department, unless a variable names several), passes on that one's approval whatever mode it holds — the outcome
  any mode would give it — so the designer does not offer the mode then, and validation does not judge it
  (`ApproverResolutionService.resolveApprovalMode` answers `AnyOne`).
- At run time the approval threshold is capped by the approvers the step has
  (`ApproverResolutionService.capRequiredApprovals`): no more approvals than approvers — a dynamic source that
  resolved fewer, or approvers dropped for being unable to act, leave a "2 of them" step asking the one left. A
  reassignment that merges two seats into one caps it by the same rule.

### Approver Resolution

Approval nodes support:

- approvers via `config.approverSources`: one or more groups, each with a `type` (`ApproverSourceType`) — specific
  people (`{"type": "Users", "userIds": [...]}`, the designer's first option; a new step starts with one such group),
  or a source that finds them when the step is reached — `Supervisor`, `DeptLeader`, `DeptHrbp`, `RoleQuery`,
  `Position`, `Department`, `VariableList`, `Expression` (and, on steps saved with them, `InitiatorManager` and
  `Role`). Each type's fields sit beside its `type`. A step had one source (`config.approverSource`), and its named
  approvers in a list of their own (`config.approvers`), before; the deploying application's release moves the one a
  step used into `approverSources` — the engine reads neither any more
- empty-approver strategies via `config.emptyApproverStrategy`: `Error` (default), `AutoApprove`, `AssignToRole`;
  `Skip` still runs on a step saved with it — approved as `AutoApprove` is, with no trace in the history — but the
  designer no longer offers it (the descriptor lists it under `deprecatedOptions`). The strategy applies only to a
  step a group of which finds its people by rule (`ApprovalNodeConfig.findsApproversByRule`): a step that only names
  its people has nobody only when none of them can act any more — its flow needs changing — and stops and says why
  whatever it holds (`PendingApprovalFactory.resolveEmptyApproverStrategy` answers `Error`); the designer does not offer
  the strategy then, and validation does not judge its roles. Steps that need a fallback when the person in a post
  leaves name the post — a role or a position — not the person

**A step's groups make one pool.** Its approvers are everyone its groups name or find, each once, in the groups'
order, and its approval mode counts over that pool: "the manager or HR" is one step of two groups under `AnyOne`,
"the manager and HR" the same under `Unanimous` — no fork drawn. A group that finds nobody leaves the others to
decide; the empty-approver strategy applies when the pool is empty, and the reason a refusal gives says why for each
group ("Ann Lee has no manager who can approve; nobody who can approve holds its roles"). Parties that must each
decide — in an order, on fields of their own (form permissions are a step's), or stopping the approval when one of
them is missing — are steps of their own, in sequence or on a `ParallelFork`'s branches.

A step names who approves it: at least one group, each a source with a type, and for specific people at least one.
One that names nobody would find nobody on every run — under the default strategy refuse every submission — and is
refused by validation and publishing (`MISSING_APPROVERS`: on `config.approverSources` for a step with no group, on
`config.approverSources.<i>` for a group with no type, on `config.approverSources.<i>.userIds` for specific people
naming nobody, whatever the vote modes; `<i>` counts from 0). Every diagnostic about a group stands on it so, and
says "group 2" when the step has several. Only specific people can be counted before the step is reached, so a
`MinCount` above the number the groups name, each once, is refused when every group names its people.

`AssignToRole` hands a step that finds nobody to roles — `config.emptyApproverRoleIds` (a tenant's own roles) and/or
`config.emptyApproverRoleCodes` (built-in roles, the same code in every tenant, so a seeded flow names them so); naming
none is `MISSING_EMPTY_APPROVER_ROLES`. Their holders who can act, found as a `RoleQuery` finds them (every company's:
a tenant that wants a company's own HR builds a role per company), less the submitter and the employee the record is
about — or the step would go back to them and pass as their own — take it, and any one of them approves or rejects
it, whatever the node's modes say (`PendingApproval.assignedRoles` names the roles). With nobody left it is refused as
under `Error`, the message naming the roles. The roles' names come from `OrganizationService.getRoleNames`.

A value a config cannot be read with — a constant name such as `ERROR`, which an earlier designer offered — is
reported against its field (`UNREADABLE_CONFIG`) instead of failing the validation; choose it again.

Supported dynamic source types:

- from start variables: `VariableList`, `Expression`; `InitiatorManager` (`initiatorManagerId`) and `Role`
  (`roleApprovers`) still resolve on a step saved with them, but the designer no longer offers them (`"deprecated":
  true` in the descriptor): they read a start variable the application must supply, and `Supervisor` relative to the
  submitter / `RoleQuery` find the same people from the organization. An `Expression` that fails refuses as a step
  that finds nobody does — "The approver expression of step '{label}' failed: …" (`400` at a submission, the
  instance failed after an approval) — instead of a server error
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
every holder; the step then follows its empty-approver strategy. An instance without a subject starts from its
initiator. The compiler refuses a scope a source does not take and a scope
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
| Code beside the name in the [directory](#directory), read only where the model has it | `Employee.code`, `Department.code` |
| The [directory](#directory)'s people, searched with the caller's row scope (`directory-model`, default the employee model; same field names) | `Employee` |

An approver needs an account that signs in: one whose `UserAccount.status` is in
`softa.flow.org.approver-account-statuses` (default `Active`). An employee without an account counts as
nobody, and so does one whose account does not sign in — not yet invited (`Pending`), invited but not accepted
(`Invited`), `Frozen`, or ended because they left (`Deactivated`) — so a step resolving only to such employees is
empty and follows its empty-approver strategy: a subordinate's request no longer waits on a supervisor who
left. A supervisor who cannot approve passes the step on up — the first one above who can takes it, as a department
whose leader cannot defers to its parent's — and levels count everyone on the chain (level 2 is still the
supervisor's supervisor); a chain that loops back ends where it would repeat. Only a chain with nobody above who can
leaves the step empty (zingkey-hcm#2447). A role holder is an account
and approves even without an employee profile, provided it signs in. A department without a leader (or HRBP)
who can approve defers to its parent's. Looking users up by id, to name them, still finds every account. The
status is not checked when the application has no `UserAccount` model. Lookups skip row scope — who approves
must not depend on what the initiator may see. Every model and field name can be overridden under
`softa.flow.org.*` (`OrganizationServiceProperties`: `account-model`, `account-status-field` and
`approver-account-statuses` among them); an application with another organization system provides its own
`OrganizationService` bean. Tasks already assigned to someone who has since left stay theirs until an
administrator reassigns them.

The same rule holds for everyone a flow or an action names, not only the organization's lookups (`ActorAccounts`,
asking `OrganizationService.getUnusableAccounts` — none unless the application's service overrides it; `FlowOrgService`
answers from the account statuses above, an account the tenant does not have counting as one that cannot act):

- **Resolved people who cannot act are passed over silently**, as a supervisor who left is: a step's specific
  people and those of every other source (`VariableList`, `Expression`, `InitiatorManager`, `Role` too), and
  a CC rule's recipients. A step left with nobody follows its empty-approver strategy.
- **An action that hands the step to someone who cannot act is refused** (`400`), naming them and why — "Ann Lee's
  account is frozen": transfer, delegate, CC (refused as a whole when it includes anyone who cannot act), an
  administrator's reassignment, creating a delegation rule (one's own or on someone's behalf), a bulk reassignment
  and its preview.
- **The designer warns** (`Warning`, never `Error`) of a step's specific people (`UNUSABLE_APPROVER`, on the
  `config.approverSources.<i>.userIds` of the group naming them) and a CC rule's (`UNUSABLE_CC_RECIPIENT`) who cannot act now, on validate and on
  publish (`ApproverAccountValidator`, a `FlowValidator` bean); an account may change after publishing, so the
  engine checks again when the step is reached. An id that is not an account id is not checked.

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
dropped, and a node with no `formPermissions` writes nothing. Every field the node lists names its permission:
one listed without — a row saved before its permission was picked, which read as no rule at all and published
as one — is refused by validation and publishing (`MISSING_FIELD_PERMISSION`, checked on the config as stored,
since the config read takes an empty permission for none), never read as `READONLY`, which shows the step's
approvers a sensitive field their own permissions withhold. A `REQUIRED` field must hold a value once the
edits land — the one sent, or else the one the record stores — so an approve that sends nothing is still
refused when the record lacks it. A blank string or an empty collection holds no value: a table sent or stored
without a row, a multi-select with nothing picked. A table's edits come as a patch — the rows created, updated and
deleted — which says nothing of the rows left, so a step does not count them: a table that must keep a row says so on
its model (`@Field(required = true)`), which the ORM holds to on every write, an approver's included
(zingkey-hcm#2449). Right after the write,
a record's approval hands the record as the edits left it,
and the fields they wrote, to its handler's `afterApproverEdit` — the model's own rules on such edits, as its page
applies them on save (values the server works out, lines that must stay consistent); throwing refuses the approve,
the edits with it, so an edit no page would have saved is refused there and then rather than when the approval's
result is applied. After the write, a record's approval reads its routing variables again from
the edited record — its own fields, then its handler's `submission` on top — and they replace the ones the
flow started with: a later route sees what the approver changed, a cleared field included. An approver whose roles hold no sensitive
field set covering a field can therefore still write it if the node's form exposes it as editable. Flow
designers must not make a sensitive field (pay, bank details, identity numbers, …) editable on an approval
form unless every approver that node can resolve to is meant to edit it; show it read-only, or leave it
off the form.

### Additional Approval Actions

A refusal names the people it is about and the step by its label (its node id when it has none):
"Cat Ng is already an approver of step 'Manager approval'." Transferring or delegating refuses someone already an
approver of the step and someone who cannot act (see [Approver Resolution](#approver-resolution)).

- transfer
- delegate
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
the flow's `returnEnabled` decides whether its approvals may be returned to the initiator at all.

`POST /flow/instances/batch/{approve,return,reject}` (`BatchApprovalExecutor`) runs one approver action over up to
100 of the caller's pending tasks with one comment — required to return or reject. Each task is its own engine
call and so its own transaction: one refused task changes nothing for the others, and every engine rule holds as
for a single action. A batch fills in nothing, so before the engine is asked it skips a task whose step makes a
field `REQUIRED` — the rule that sets a task's `batchable` flag — and, for a return, one whose flow does not set
`returnEnabled` (its `returnAllowed`). The result (`FlowBatchActionResult`) gives the succeeded / skipped / failed counts
and each task's outcome with the reason, in request order.

### Overdue Reminders

An approval flow's `remindAfterHours` (a flow setting, like the urge interval) makes each of its steps due that many
hours after the step opens (`dueTime`, written on the task: My Tasks shows it, and marks a task past it overdue). When
a step falls due its approvers still to act are reminded, then again every `remindAfterHours`, at most 3 times
(`ApprovalTimeoutScheduler.MAX_REMINDERS`, not configurable). Without the setting a step is never due and nobody is
reminded. The reminder is an inbox notice ("Approval overdue"), not emailed.

A reminder is all that happens: nothing is approved, rejected or handed on for anyone. In HR and finance approvals
silence is not consent, and a rejection would make the submitter pay for an approver's delay. A step nobody acts on
goes on waiting; the ways out are a person's: its approver delegates (or sets a delegation before leaving), its
submitter urges, an administrator reassigns it. Steps have no timeout of their own — a `timeout` a step was saved with
before (deadline, strategy, reminders, escalation target) is ignored when it is read, and decides nothing.

`ApprovalTimeoutScheduler` reminds against the persisted pending approvals, reading the interval from the revision an
instance runs on; a step due under a setting its revision no longer has is reminded once. It runs off a cron row,
`flow_approval_timeout_handle`, once per tenant in the tenant's context (`PerTenant`), so the reminders and the
instances they update are the tenant's; so does the timer sweep, `flow_timer_sweep`, which resumes instances. (A
cross-tenant run has no tenant to stamp on the rows it inserts.) The starter's maintenance crons (approval
reminders, delegation expiry, timer sweep, stuck-instance scan, automation run purge) ship switched
off in `data-system/SysCron.FlowMaintenance.json`. The app lists that file under `platform` in its
`seed-manifest.yml`, or the rows never exist; then switch on the ones it uses. See the
[seed-starter README](../seed-starter/README.md). Loading the file again writes its `active: false` back over every
row: switch the crons the app uses back on after it.

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
- The input may also sit at the top of the config, one key per field, beside the node's own keys
  (`outputVariable`, `outputMapping`, `options`) — the way the designer writes it, field by field as the
  executor's descriptor (`getConfigSchema`) lists them. With no `input` map, everything else at the top is the
  input (`NodeConfigParser`); a config may also wrap either form in `task`. Read only from `input` before, a
  designed task node ran with no input and published without its required fields checked (zingkey-hcm#2384)

Every task node type has a **typed input config DTO** registered in `TaskConfigTypes`:
the compiler parses the input into the DTO at publish time and rejects missing
required keys (`MISSING_REQUIRED_INPUT`), naming the field where it sits — `config.input.url` in a nested
config, `config.url` in a designed one — so a misconfigured node fails at publish
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
| Freezing every record that is not a draft (`ApprovalLockValidator`) | `submission(record)`: an `ApprovalSubmission(title, variables, effectiveDate, code, summary)` — the instance title and the routing variables it adds to the record's own fields (*Routing variables*), worked out from the record alone, and, when the model has them, the date the record takes effect and its own number, which the approval lists show and filter on, and a `summary` line saying what the record asks for (a leave's days, a claim's amount), worded with `ApprovalSummaries`' pieces from what everyone the approval reaches may see — never a sensitive field — kept on the instance (`FlowInstance.summary`, at most 256 characters, cut beyond) and worked out again after an approver's edits |
| Writing each result back, once | `afterSubmitted(record)`: what a submission sets off, such as reserving a leave balance |
| Writing an approver's edits — only the fields their step lets them change | `afterApproverEdit(record, editedFields)`: the model's own rules on what an approver changed, as its page applies them on save — values the server works out, lines that must stay consistent; throwing refuses the approve, the edits with it |
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
— until an approver first approves, rejects, returns, transfers or delegates at any step; an automatic approval
does not count — or `UntilCompleted`. Whatever the policy, only the initiator withdraws, and
only while the approval waits on its approvers. A record's approval that failed is the exception: nobody is left to
act on it, so its submitter takes the record back whatever the policy, while the record still waits on that approval
(`WithdrawPolicyGuard.takesBack`) — a draft again, to submit on the flow as it is by then; the failed instance stays
for flow monitoring. `WithdrawPolicyGuard` is the one rule: the withdraw actions enforce it, and the preview,
`GET /flow/instances/{instanceId}/withdraw` and the instance search's `withdrawable` report it, so a Withdraw button —
on the record's page, the approval page or My Requests — is offered exactly when the withdrawal would be accepted.

| `FlowClient` | What happens |
|---|---|
| `submitRecord(modelName, rowId)` | The submitter must reach the record within the range their roles may edit it in: submitting writes it, so the range counted is that of the submitter's roles holding the model's update action (`AccessScope.callAs(UPDATE, …)`), not the wider one of every role that may view it — a role that only views a department's requests submits none of them. A submitter none of whose roles holds the update action keeps the range they read in. Only a draft with no approval in progress is submitted. `handler.validate` then checks the whole record as the submitter, and `handler.submission` returns the instance title and the routing variables it adds to the record's fields. The flow is chosen among the tenant's enabled Approval flows of the model by their submit conditions, read over the variables the approval would start with — exactly one must hold, else the submission is refused naming why. The record becomes `Pending`, with its submitter and the time, `handler.afterSubmitted` runs, and the record's approval starts. All in the caller's transaction, which a start that fails rolls back whole (see below). Every submission starts a new approval from the beginning — after a return too, on the revision effective now — and marks the record's earlier approvals `superseded` |
| `withdrawRecord(modelName, rowId, comment)` | Withdraws the waiting approval (the engine checks the actor is the initiator and the flow's `withdrawPolicy` permits it); the record is a draft again. When the record's approval **failed**, its submitter takes the record back the same way — the handler is told it was withdrawn; the failed instance stays for flow monitoring. Refused when the record has no approval in progress |
| `cancelRecord(modelName, rowId)` | Cancels a draft for good: the applicant must reach the record within the range their roles may edit it in, as for `submitRecord`, it must be a draft, and `handler.validateCancel` must pass. The record becomes `Cancelled` and `handler.afterOutcome` gets a `Cancelled` result with no instance; the record's earlier approvals are marked `superseded`, so one returned no longer waits for the record to be edited. In the caller's transaction |
| `recordApprovals(modelName, rowId)` | The approval history, as `GET /flow/instances/by-record` |
| `previewRecord(modelName, rowId)` | The approval a submission would start, as `GET /flow/instances/preview` — see below. Call it outside a transaction |
| `previewValues(modelName, values)` | The same for a record not saved yet: its field values, as they are stored (ids, codes) and the subject among them, stand for the row — a draft, so only the subject and the handler's `validate` / `submission` are worked out, from the values alone. For a record the application files as it submits it (a pay run's approval request, created by the run's own submit), whose route shows before it exists. No endpoint serves it: the caller's own endpoint decides who may ask. Outside a transaction, as `previewRecord` |

Only the instance a submission starts is the record's **approval** (`FlowExecutionState.recordApproval`): an
automation run on the same record is not, and is never written back (an API start of an Approval flow is
refused — its trigger is `Submit`). A record has
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
leaves its record `Pending`, with two ways out — three when it failed after an approval: its
submitter takes the record back by withdrawing it (a draft again), an administrator terminates the approval (the
record `Cancelled`), or an administrator runs the steps after the approval again (see
[When a step after an approval fails](#when-a-step-after-an-approval-fails)). The record becomes `Pending` before the instance starts
because a flow with no approval step completes while it starts.

A record's approval starts within its submission, so a start that fails fails the submission, atomically: the
exception reaches the caller, nothing is kept — no failed instance — nobody is told, and the record rolls back with
the transaction, still a draft. A step whose approvers resolve to nobody under its empty-approver strategy `Error`
(the default) refuses with `FlowNoApproverException`, a `FlowActionValidationException` (`400`): "No approver was
found for step '{label}': {why}. Ask your HR administrator to set one up, then try again." — the why by its source
(`ApproverResolutionService.whyNobody`): "Alice Tan has no manager who can approve", "… 2 levels up …", "the submitter
has no employee profile", "Finance and the departments above it have no leader who can approve", "nobody who can
approve holds its roles", "none of its approvers can approve", and so on; without one, the sentence ends at the
label. Under `AssignToRole`, when its roles leave nobody either, the message names them. The preview's `failure`
carries the same message, and a form's Submit says it instead of submitting when the submission would meet that
step — before any step that waits for someone. An approve that
moves the approval into such a step keeps the approval and fails the instance with that message (see
[When a step after an approval fails](#when-a-step-after-an-approval-fails)): the approver cannot set up an approver.
So a record's approval fails only after its start: when the approval cannot go on after an approval, or when a timer
or an async callback resumes it into an error; the record's approval preview shows a step that would find nobody
before anyone submits.

### When a step after an approval fails

An approval runs what follows it in the approver's request (`FlowExecutionOrchestrator.runAfterApproval`), and
nothing that stops it going on there undoes the approval (zingkey-hcm#2385): an automated step failing — a task
(`CallWebhook`, `SendEmail`, `UpdateRecord`…), a data step, a subflow that cannot run (its flow not published or
disabled, not an Automation flow with a Subflow trigger, calling a flow already running above it, or stopping to
wait) or whose steps fail — no route out of a step (#2382), nobody to approve the next step, branches meeting a step
wrongly. The approver has done their part, and none of these is
theirs to put right. Before the steps run, the instance is copied as the approval left it and a savepoint taken;
when one of them happens:

- its writes are undone to the savepoint, apart from the approval, and so is what the steps arranged for the
  commit (`UndoableSteps`): the callbacks they registered — messages to send once it commits — are dropped, each
  told it rolled back, and the change logs they added go too, with the flows those would have started;
- the error is logged (`ERROR`, with the stack trace);
- the instance, as the approval left it, fails at the step that stopped it — the approval step itself when its own
  routes led nowhere — (`FAILED`, `failedNodeId`, `errorMessage`), remembering the approval step to run again after
  (`retryAfterNodeId`); its initiator and the tenant's administrators are told ("Approval failed: …", with the
  error);
- the approver's request goes through: the approval stands, and they get the failed instance, not the error. The
  record stays `Pending`.

What the approver can put right is refused before any of this, the approval with it: a step already handled, a
required field left empty, an edit the record's rules refuse (`afterApproverEdit`). So is the record's handler
vetoing the result (`afterOutcome`), which runs as the instance is saved. Then the submitter can take the record back
— a draft again, submitted on the flow as it is by then: the way out when the gap is in the flow itself, since a
running instance keeps its revision — or an administrator runs the steps again once the cause is gone, or terminates
the approval.

An administrator then runs the steps after the approval again — `POST /flow/monitor/instances/retry`, granted with
terminating (`RetryActionHandler`): the instance goes back to `Running` from the step it failed after, the history
records a `RETRY` entry by them, and the steps run as they would have after the approval, permission checks set
aside. Should a step fail again, the instance fails again and the same people are told again; once they run through,
the approval goes on — and completes, its `onApproved` CCs sent, as it would have. Only an instance with a
`retryAfterNodeId` runs again: one that failed otherwise — an automation run, a timer or a callback — is terminated
instead. A record's approval runs again only while the record waits on it: not once its submitter took it back (a
draft again), nor once it was submitted again. The steps run again in full — what one of them sent before it failed,
an email or a webhook call, may be sent again — so a step that must not repeat checks for itself.

Nothing in a run fails an instance but a step that throws: a subflow that cannot run throws too
(`FlowExecutionOrchestrator.executeSubflow`), so it is handled like any failing step — at submission the submission
is refused, after an approval the approval stands as above, in an automation run the run fails.

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
| `Waiting` | Waits for `approvers`, of whom `requiredApprovals` must approve; `assignedRoles` names the roles they hold when the step found nobody and went to roles (`AssignToRole`) |
| `AutoApproved` | Passes on its own: `autoApprovals` counts as having approved the submitter (`Submitter`), the employee the record is about (`Subject`) — under the flow's `selfApproval` — or someone who approved an earlier step, under the flow's approver dedup (`EarlierApproval`) |
| `NoApproverApproved`, `NoApproverSkipped` | Resolves to nobody; its empty-approver strategy approves it on record or skips it |
| `NoApproverFails` | Resolves to nobody under strategy `Error` — or `AssignToRole`, its roles leaving nobody either: the submission would be refused there, with the message `failure` gives |
| `Undetermined` | Who approves it, or whether it passes on its own, depends on data only known once the approval runs |

Nothing is guessed. The preview runs no node: once the walk passes a script, a task or a subflow, the variables
that node writes are unknown — all of them for a task without an output variable and for an async task — and
the instance id is unknown from the start. A route whose conditions read an unknown variable is listed in
`undecided` and not followed; a step whose approvers come from a dynamic source, reached after such a node, is
`Undetermined`; with approver dedup on, so is a step whose approver may or may not have approved an earlier
step, one of several there who could pass it. An error the engine would fail on — a route matching no edge, a
condition that cannot be evaluated — ends the walk in `failure`.

Nothing is written: no instance, task, ledger row or notification, and no delegation's use is counted. The
preview is allowed to whoever can read the record with their own permissions
(`FlowInstanceAccessGuard.requireRecordReader`) and is never waived from row scope; taking part in the record's
earlier approvals is not enough. Users are named as in the approval history. It runs in no transaction of its
own, and `FlowClient.previewRecord` should be called outside one: a refusal thrown through a transactional
method marks the transaction it joins rollback-only.

### Testing a draft

The designer's test run of an approval flow is the same preview on the flow's saved draft:
`POST /flow/designs/{id}/preview` with `{submitterId, values}` (`FlowLaunchService.previewDraft`) answers what
submitting a request with those field values — filled in on the spot, never saved — as that user (the current
user when none is named) would do under the draft. The draft is compiled, never registered (a draft that does
not compile returns its diagnostics, `400`); a flow that is not an approval flow is refused. The employee the
request is about is the submitter's own when the values name none, the request's handler works out its
`submission` but not its `validate` — a test run tries the flow, not whether this request may be submitted now
(a pay run not calculated yet still shows who would approve it) — and `ApprovalPathPreviewer` walks the draft as
above. `FlowDesignPreview` returns `submitConditionMet` (whether the draft's submit condition takes the request:
when it does not, a submission would not come to this flow at all), the `submitter` named, and the `route` —
with `nodeIds`, every node it passes in order, which the canvas marks. A request that names no employee, or a
model with no approval handler, is reported as `Refused`. Nothing is started, written or sent, and no
delegation's use is counted. Automation flows have no test run: a run of one does what its nodes do.

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
  `recordCode` and `link` — the approval's page, `system.site-url` + `/home/approvals/{instanceId}`, empty when the
  site URL is not configured. flow-starter ships no mail templates: a tenant without a template of the code gets
  no email, and the failure is logged.

One mapping (`ApprovalNotices`) decides who is told what, on both channels:

| Event | Told | Inbox | Mail template |
|---|---|---|---|
| A step's approvers still to act are assigned; a task is transferred, delegated or reassigned to someone | The approvers still to act; the new holder | yes — "Pending approval" (also in My Tasks) | `flow.task-assigned` |
| A CC | Its recipients | yes — "For your information", with the CC's message (also in My Tasks) | — (not emailed) |
| An urge | The approvers urged | yes | `flow.urged` |
| A step falls due (the flow's `remindAfterHours`), and every as many hours after, at most 3 times | Its approvers still to act | yes | — (not emailed) |
| Approved; rejected or returned (with the comment) | The initiator | yes | `flow.approved`, `flow.rejected`, `flow.returned` |
| Terminated, or a step reassigned, by an administrator (with the reason) | The initiator | yes | `flow.terminated`, `flow.reassigned` |
| A record's approval fails once started — the approval unable to go on after an approval (the approval kept, see [When a step after an approval fails](#when-a-step-after-an-approval-fails)), a timer or callback resuming it into an error; with the error. A submission that cannot start tells nobody | The initiator and the holders of the roles in `flow.notification.failure-role-codes` (default `TENANT_ADMIN`) | yes | `flow.failed` |

Only an Approval flow announces its completion: an Automation run is not something the user whose write triggered
it asked to hear about, and an automation run that fails is the operators' to watch. Messages are written after
the action commits, in a transaction of their own: a rolled-back action sends nothing, and a message that cannot
be written is logged without failing the action. A failure is told at once, since the failed attempt's own
transaction rolls back.

`InboxReadMarker` keeps the inbox current: once an approver acts on an approval — approves, rejects, returns,
transfers, delegates or reads a CC — their unread inbox messages about that instance (the pending
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
| `mq.topics.flow-timer.topic` | `FlowTimerProducer`, `FlowTimerConsumer` | Timer wake-up: a delayed message per Timer node, and the resume |

Important timer note:

- With `mq.topics.flow-timer.topic` set, a Timer node schedules its own wake-up — `FlowTimerProducer` sends a
  delayed `FlowTimerMessage` for the instant the node computes — and `FlowTimerConsumer` resumes the instance when it
  arrives. Nothing else has to publish it.
- Without the topic, or for a wake-up lost on the way, only the maintenance cron `flow_timer_sweep` resumes the
  timers that are due (up to 200 a run, once per tenant); like the other maintenance crons it ships switched off
  (see [Overdue Reminders](#overdue-reminders)).

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
| `FlowInstance` | Persisted runtime instance state (trace and approval history are NOT embedded — see the two dedicated tables), with what a record's approval recorded at submission: `subjectId`, `departmentId`, `effectiveDate`, `recordCode`, `superseded`; and, on an instance whose steps after an approval failed, `retryAfterNodeId` — the approval step they run again after. Its `variables` are JSON, which the app writes a `Long` or a `BigDecimal` into as text: on load the record's routed fields come back as numbers (`RecordVariables.restoreNumbers`), so a route after an approval compares an amount or an id as a number, not as text that fails the comparison or never matches (zingkey-hcm#2382); an id stays text where the related model is known by a code. Approval instances are kept; finished Automation runs, with their trace, branches and the trigger event log, are purged after `flow.automation.retention-days` (default 30) by the `flow_automation_purge` cron |
| `FlowExecutionTrace` | Append-only execution trace rows (the in-memory state keeps only the current attempt's delta) |
| `FlowApprovalTask` | Pending/completed/CC task projections, each with its instance's list fields copied in (`instanceTitle`, `recordCode`, `submittedTime`, …) for the approval center's search |
| `FlowApprovalRecord` | Approval action audit ledger — the authoritative approval history, flushed by the instance store in the same transaction as the instance row; `operatorId` names an administrator who acted on the actor's behalf |
| `FlowDelegation` | Delegation rules |
| `FlowEvent` | Trigger event logs |
| `FlowParallelBranch` | Parallel branch tracking |

Primary storage adapters in this module:

- `OrmFlowBundleRegistry`
- `OrmFlowInstanceStore`

`OrmFlowBundleRegistry` loads nothing at startup and caches per tenant:

- **Trigger matching** reads an index of the active bundles of the tenant's enabled flows, loaded in that tenant's own context on its first event (an empty result is kept too) and merged with the platform-shared bundles (`tenantId = 0`); a context without a tenant matches the shared ones only. Entries live for `flow.trigger-cache-ttl-seconds` (default `60`). Publishing, activating a revision, or disabling or enabling a flow drops the tenant's entry at once on the instance that did it; other instances pick the change up within the TTL. Change logs of models no flow watches are not turned into trigger events.
- **Definitions** are cached by `bundleId` in a bounded LRU (a published revision never changes). A bundle of another tenant reads as not found, whether cached or not; platform-shared bundles are visible to every tenant.
- **The active revision of a flow** is read from the database on every lookup — none while the flow is disabled — so starting by `flowId` follows a publish, rollback, disable or enable made on any instance.

### Stored enum codes

The flow models store enum codes as text: in their enum columns (`scenario`, the statuses, approval actions) and in
their JSON — `FlowDesign.designJson`, `FlowBundle.designJson` and `FlowBundle.compiledJson` hold the scenario, the
trigger and node types, every option inside a node's config and an approver source's `type` / `relativeTo` /
`deptScope`, and an instance keeps its state as JSON. A read throws on a code the enum no longer has (unknown enum
values are deliberately not read as null), so **renaming or removing a stored code ships a migration SQL that
rewrites the stored rows, in the same change**. Without one, every flow still holding the old code fails to load,
publish or reapply — as the flows stored as `Process` did when that scenario was split into `Approval` and
`Automation`. Renaming a key inside the JSON needs the same: an unknown key reads as absent, so its value is silently
lost. The model is zingkey-hcm's
`deploy/hcm-app/release/Release-20261030/20261029-approval-center/01-before-jar-withdraw-policy.sql`, which moved
`allowInitiatorWithdraw` to `withdrawPolicy` in all three JSON columns, text or jsonb.

`StoredDesignJsonTest` reads designs as databases hold them (fixtures in `src/test/resources/stored-designs`) with
the runtime's Jackson settings and fails the build when an enum change strands one. A failing fixture means writing
the migration, never editing the fixture; add a fixture when a new design shape reaches a database.

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
- an approval step's `approverSources` offers its sources in the order an HR administrator reaches for them —
  specific people, the organization's (supervisor, department leader, HRBP), the groups' (role, position, department
  members), then the developers' (variable list, expression), then those no longer offered (`deprecated`) — each
  option saying what the editor needs to tell which settings apply: `names` (the field a group of specific people
  names them in, `userIds`) and `findsOne` (the source finds one person at most). A setting that applies only to some
  steps says so in `appliesWhen`: `severalApprovers` (the approval mode, `minCount`, `percentage`) or
  `approversFoundByRule` (the empty-approver strategy and its roles); the engine ignores it elsewhere, by the same
  rules, so the editor shows it only where it applies

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
- check the organization follows the default HR conventions, or override `softa.flow.org.*`, or provide your own `OrganizationService` — implementing `getUnusableAccounts` too, or every account counts as able to act, and `getRoleNames`, or a step handed to roles (`AssignToRole`) names them by id and code
- list `GET /flow/delegations/flows` with the permission that reads delegation rules (the Flow and step dropdowns of a rule scoped to one flow or step, and the step a listed rule names)
- list the directory reads — `GET /flow/directory/people` and `/people/by-ids` for people, `/departments` and `/departments/by-ids` for departments — in the permissions of the pages whose pickers use them (approving, managing one's delegations, the designer, the monitor), and `/people/all` and `/people/all/by-ids` in the designer's and the monitor's alone, not in `permission.authenticated-bypass-patterns`, where they would skip the endpoint gate (see [Directory](#directory)); point `softa.flow.org.directory-model` at a projection of the employees that shows only what a directory does, and give every role a scope on it — a role without one finds nobody in the pickers; an application with its own `OrganizationService` implements the five directory methods, or the pickers find nobody
- for business records: extend `ApprovalModel` (with an `employeeId`, or a `subject()` naming the employee field) and `ApprovalModelController`, implement a `ModelApprovalHandler<T>` per model — with `effectStatuses` when its approval takes effect later — and ship each default flow as a tenant seed file
- grant the monitoring console's endpoints and a data scope on `FlowInstance` to the administrators' roles (a department scope narrows by the subject's department)
- list the per-instance reads the approval page makes — `GET /flow/instances/{instanceId}` and its `/summary`, `/design`, `/record`, `/overlay`, `/trace`, `/urge`, `/withdraw` — and, for a page keyed by the record, `GET /flow/instances/by-record/record`, in the permissions that open approvals: the endpoint gate refuses an endpoint no permission lists, and the access guard decides the rest
- list `POST /flow/approvalTasks/search` in the permission that reads the user's own tasks — it replaced `GET /flow/approvalTasks/pending`, `/completed` and `/cc` — and ship the SQL that fills the task list fields of existing rows (see [Approval Tasks](#approval-tasks))
- enable Pulsar topics if using triggers or async tasks
- provide timer scheduling/publishing if using `TIMER` nodes
- enable `CALL_SERVICE` explicitly (plus its bean-name allow-list) if flows need to invoke Spring beans; all other task node types ship working executors, conditionally registered on their capability beans
