# Flow Starter

`flow-starter` is Softa's flow engine. A designer draws a flow; publishing compiles it into an immutable revision; the
runtime runs revisions as persisted instances. An **Approval** flow approves one business record — its steps are
people and routes, and its outcome is written back to the record. An **Automation** flow runs tasks when data
changes, on a schedule, through the API, or as another flow's subflow.

This README is the maintainer reference: what the module is, how it is laid out and wired, its extension points and
their contracts, and the invariants a change must keep. What the engine does — its rules, settings, APIs,
diagnostics, notifications and the migrations an upgrade needs — is documented once, in the softa-docs Flow pages
(see [Documentation](#documentation)).

## What it is, and what it is not

It is:

- a graph document for a visual editor (`FlowDesign`, `DesignFlowDefinition`) and a compiler that validates it and turns
  it into a published revision (`FlowBundle`, `CompiledFlowDefinition`), one active revision per `flowId`;
- a runtime that runs revisions as instances (`FlowInstance`, `FlowExecutionState`) and keeps their projections: the
  approval ledger, approval tasks, the execution trace, delegation rules, trigger events;
- the approval of business records: `ApprovalModel`, `ApprovalModelController` and the `ModelApprovalHandler` SPI;
- REST controllers under `/flow/**`, the in-process `FlowClient` port, and optional Pulsar integration.

It is not:

- a BPMN engine: there is no BPMN import, and a `ParallelFork`'s branches run one after another, in order — a fork
  models routing, not concurrency;
- a place for business rules: a record's validation belongs to its model (field constraints, a `ModelWriteValidator`)
  and a value worked out from others to a computed field. The Validation and Compute scenarios, the `FieldChange`
  trigger and `declaredOutputs` that once stood for them never took effect and are gone;
- where an approval's side effects run: an approval flow holds people and routes only, and what its outcome should set
  off — a webhook, a record to write, a message — is an Automation flow started by the record's change;
- a remote contract: `FlowClient` is in-process; another service calls the REST API.

## Package layout

Under `io.softa.starter.flow`:

| Package | What lives there |
|---|---|
| `api` | `FlowClient`; the compile diagnostics (`CompileDiagnostic`, `FlowCompileException`) |
| `design`, `design/trigger` | The editor's graph (`DesignFlowDefinition`, `FlowGraphNode` / `FlowGraphEdge`, `FlowCcRule`), node descriptors, the trigger types (`TriggerSource` and its sealed sub-types) |
| `compiler` (`graph`, `transform`, `validation`) | `DefaultFlowCompiler`: graph indexing and ordering, the built-in `FlowValidator`s, the transform into `CompiledFlowDefinition` |
| `entity`, `enums`, `dto` | The persisted models — `FlowDesign`, `FlowBundle`, `FlowInstance`, `FlowExecutionTrace`, `FlowApprovalTask`, `FlowApprovalRecord`, `FlowDelegation`, `FlowEvent` — and `ApprovalModel`, the base of an approved record; wire enums and DTOs |
| `controller` | The REST surfaces, `ApprovalModelController`, `FlowExceptionHandler` |
| `service` (`impl`, `query`, `support`) | Design and publish, launch (submit, preview, test run), instance reads and searches, tasks, delegation, the directory, the monitor; `FlowInstanceAccessGuard`, `CallerConditions`, `SubmitConditions`, the task projector |
| `runtime/engine` | `FlowRuntimeFacade` (the `FlowRuntimeEngine`), `FlowExecutionOrchestrator`, approver resolution and dedup, auto-CC and auto-delegation, the path previewer, the approval lock and status writer, `WithdrawPolicyGuard`, reminders, `UndoableSteps`, `FlowCron`, `ActorAccounts` |
| `runtime/action` | One handler per action: approve, reject, return, transfer, CC, read CC, comment, urge, withdraw, terminate, retry, reassign |
| `runtime/handler`, `runtime/task` (`builtin`), `runtime/nodeconfig` | Node execution handlers; the `TaskExecutor` SPI and the bundled executors; typed node configs and `TaskConfigTypes` |
| `runtime/bundle`, `runtime/store`, `runtime/state` | Compiled revisions and `OrmFlowBundleRegistry`; `OrmFlowInstanceStore` and the approval ledger; `FlowExecutionState` and its parts |
| `runtime/trigger` | `DefaultFlowTriggerRegistry` — which flows an event or a submission starts — and `FlowAutomationService` |
| `runtime/spi` (`impl`) | `ModelApprovalHandler`, `OrganizationService`, `ApprovalNotificationService` and their defaults |
| `runtime/api`, `runtime/exception`, `runtime/context`, `runtime/descriptor` | Action requests; exceptions; the variable context and `RecordVariables`; the node descriptors (`FlowNodeDescriptorRegistry`) |
| `message` | Pulsar producers and consumers, `CronFlowJobProvider`, `FlowMaintenanceCronConsumer` |
| `event` | `SeedFlowPublisher`; `TransactionListenerForFlow`, which runs sync Automation flows before a write commits |

## Wiring and configuration

`FlowAutoConfiguration` (listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`)
component-scans the package. The beans it declares:

| Bean | Registered when |
|---|---|
| `FlowOrgService` | No `OrganizationService` bean |
| `FlowClient` | Always: one class, nothing to replace |
| `MessageApprovalNotificationService` (inbox and email, through message-starter) | No `ApprovalNotificationService` bean |
| `InboxReadMarker` | Always |
| `flowDeadLetterPolicy` | Pulsar on the classpath and no bean of that name: at most 3 redeliveries, then `<topic>-<subscription>-DLQ` |
| `SeedFlowPublisher` | seed-starter on the classpath (`SeedFileLoadedEvent`, checked by name) |
| `MetadataFieldDefinitions` | metadata-starter on the classpath (`MetadataDtoMapper`, checked by name) |

`FlowOrgService` and `MessageApprovalNotificationService` yield to the application's own, and are `@Bean`s of the
auto-configuration rather than scanned components on purpose: a scanned `@Component` with `@ConditionalOnMissingBean`
can fail to register depending on scan order, which once left the dynamic approver sources resolving to nobody.

Dependencies: softa-web; cron-starter, message-starter, file-starter and ai-starter at compile scope, whose executors
and consumers register only when the bean they need exists (`MessageService`, `AiRobotService`,
`DocumentTemplateService`, `FlowAsyncTaskProducer`); seed-starter and metadata-starter optional.

| Property | Default | Read by |
|---|---|---|
| `flow.automation.retention-days` | `30` | `FlowAutomationPurgeService` |
| `softa.flow.org.directory-model` | the employee model | `FlowOrgService`: the model the people pickers search, with the caller's row scope |
| `system.site-url`, `system.enable-multi-tenancy` | — | The links in approval emails; the per-tenant fan-out of Cron flows |

Each Pulsar component is `@ConditionalOnProperty` on its topic:

| Topic | Components |
|---|---|
| `mq.topics.change-log.topic` | `ChangeLogFlowConsumer` — change logs into `EntityChange` events (`ChangeLogTriggerMapper`); runs the async flows they start in its own thread |
| `mq.topics.cron-task.topic` | `CronFlowJobProvider`, `CronTaskFlowConsumer`, `FlowMaintenanceCronConsumer` |
| `mq.topics.flow-async-task.topic` | `FlowAsyncTaskProducer`, `FlowAsyncTaskConsumer` |

The maintenance crons ship switched off in `data-system/SysCron.FlowMaintenance.json` — `flow_approval_timeout_handle`
(`PerTenant`), `flow_automation_purge` (`CrossTenant`) — and `FlowMaintenanceCronConsumer` ignores every other cron
name. A Cron-triggered flow writes no `SysCron` row: `CronFlowJobProvider` schedules one job
per active revision, `flow:<bundleId>`, and `CronTaskFlowConsumer` starts only a revision still active.

## Extension points

**`ModelApprovalHandler<T>`** (`runtime/spi`) — one Spring bean per approved model, collected by
`ModelApprovalHandlerRegistry` once every singleton exists (a handler that also submits through `FlowClient` would
otherwise close a construction cycle). The boot fails on two handlers for one model, on a model whose metadata lacks
the approval fields `ApprovalModel` declares, and on a field for the employee the record is about that is not a
many-to-one onto `Employee`; `subject()` names that field (the instance's `subjectId`), and
`null` — the default — means `employeeId`.
`submission` (required) works from the record alone, and its `summary` names no sensitive field: everyone the
approval reaches sees it. `validate` and `validateCancel` run as the user. `afterSubmitted`, `afterApproverEdit` and
`afterOutcome` run in the action's transaction with permission checks off, so throwing vetoes the action; they write
to this database only and leave external effects to after the commit. `effectStatuses` reads the records, never
guesses from dates.

**`OrganizationService`** (`runtime/spi`) — how the engine reads the organization. Lookups skip row scope — who
approves must not depend on what the submitter may see — except `searchPeople`, which reads the directory model with
the caller's row scope. An implementation overrides the defaults that matter: `getUnusableAccounts` (none: every
account counts as able to act), `getRoleNames` (none: a step handed to roles names them by id and code) and the five
directory methods (empty: the pickers find nobody). `FlowOrgService` implements them all over the HR models —
`Employee`, `Department`, `UserRoleRel`, `Role`, `UserAccount` — by the field names permission-starter reads, fixed in
the class; an application on other models implements `OrganizationService` itself.

**`ApprovalNotificationService`** (`runtime/spi`) — `notify(FlowNotificationEvent)`, called inside the action's
transaction. `ApprovalNotices` maps each event to its recipients, inbox text and mail template, once per event. The
default, `MessageApprovalNotificationService`, writes the inbox message and the email after the commit, each in a
transaction of its own, except `FlowFailed`, told at once because the failed attempt's transaction rolls back; a
replacement owns that timing. A bean of the application's replaces both channels; to add one, declare a bean that calls
a `MessageApprovalNotificationService` first, then sends yours.

**`TaskExecutor`** (`runtime/task`) — one per `FlowNodeType`: two for one type, or a type without a typed config in
`TaskConfigTypes`, fail the boot (`DefaultTaskExecutorRegistry`). `getConfigSchema` and `getDefaultConfig` feed the
palette through `FlowNodeDescriptorRegistry`, which lists only the executors registered and gives each node an
`outputVariable` field after the executor's own. Task nodes are
Automation-only. A new task node type is a `FlowNodeType` constant with its category and scenarios, a config DTO in
`TaskConfigTypes` and an executor; any other node type needs a `NodeExecutionHandler` (one per type,
`DefaultNodeHandlerRegistry`) and a descriptor in `BuiltinNodeDescriptorProvider` instead.

**`FlowValidator`** (`compiler/validation`) — `DefaultFlowCompiler.builtinValidators()`, the one list the Spring path
and the no-arg test path share, plus every host bean. A validator returns every diagnostic rather than throwing; only
an `Error` stops a publish. Structural errors (missing or duplicate ids, unknown edge ends) are reported alone, before
any validator runs.

**`FlowClient`** (`api`) — not an extension point but the in-process port, one class, for what a business module does
with a record's approval: `submitRecord`, `previewValues`, `withdrawRecord`, `cancelRecord`. Identity comes from the
context, never from a parameter. Approver actions, task reads, a record's approval history, a saved record's preview and starting an
`Api`-triggered Automation flow stay on REST; a method joins the port when a business module calls it, not before.

**`FlowStateChangeListener`** (`runtime/engine`) — called by `FlowProjectionPublisher` after every save of an
instance's state, in the same transaction, with permission checks off: the projections that follow each action are
such listeners — the task projection (`FlowApprovalTaskServiceImpl`) and `InboxReadMarker`. **`FlowStatusChangeListener`**
— called after those, only when the save moved the instance's status, with the status the action found it in
(`FlowExecutionState.loadedStatus`, set when the state is read; `null` for a new instance). The publisher decides this
once, from the state, never from what the action recorded — an approval records the CCs its result sends after the
entry that ended it — and announces a move once. The record write-back (`ApprovalOutcomeListener`, through
`ApprovalStatusWriter`) is one. A listener of either kind that throws rolls the action back.

**`FlowNodeDescriptorProvider`** (`design`) — contributes palette descriptors; `BuiltinNodeDescriptorProvider` and
`TaskExecutorDescriptorProvider` are the bundled ones.

**`ActorAccounts`** (`runtime/engine`) — the one place that decides whether accounts can act (`usable`,
`whyUnusable`, `requireUsable`, over `OrganizationService.getUnusableAccounts`). Whatever names people goes through
it: approver resolution, auto-delegation, the action targets (`ApprovalActorValidator`), reassignment, delegation
rules and the designer's warnings (`ApproverAccountValidator`).

## Invariants

A change keeps these; each names the class that holds it.

- **A record's approval state belongs to the framework.** `ApprovalLockValidator` (a `ModelWriteValidator`, order 10,
  at the write roots) freezes a record that is not a draft, and the records its to-one cascade owns, and keeps the
  approval fields out of every write; writes made with permission checks off are exempt. Results reach the record only
  through `ApprovalOutcomeListener` and `ApprovalStatusWriter`: on the save that moved the instance into its result,
  only onto a record still `Pending`, so `afterOutcome` runs once per result. An instance's status moves only by `FlowStatusTransitions`.
- **One approval in progress per record.** `FlowInstance.openRecordId` holds the record's id while its approval is
  unfinished (`FlowExecutionStateMapper`), under the unique index `uk_flow_instance_open_record` (tenant, model,
  record): a second submission fails even when two arrive at once.
- **A failure after an approval keeps the approval.** `FlowExecutionOrchestrator.runAfterApproval` copies the instance
  as the approval left it and runs what follows inside `UndoableSteps` — a savepoint, the commit callbacks and change
  logs added there dropped with it. When those steps fail, the instance fails at the step with `retryAfterNodeId` and
  the approver's request still succeeds; `RetryActionHandler` runs the steps again from there. What the approver can
  put right is refused before, the approval with it.
- **Approval flows hold people and routes only.** `FlowNodeType` declares each type's scenarios (`Scenarios.BOTH`,
  `APPROVAL_ONLY`, `AUTOMATION_ONLY`); `GraphStructureValidator` refuses any other node in an approval flow at publish
  (`INVALID_SCENARIO_NODE`), and the palette filters by the same declaration. That is what lets the steps after an
  approval run again without repeating anything. A revision published before the rule keeps running as it was.
- **Completion side effects happen in one place.** `FlowExecutionOrchestrator.finalizeRootState` sends the
  `FlowCompleted` notice and runs the `OnApproved` CC rules (`AutoCcService`, recorded on the approval step completed
  last), whichever run completed the instance — an approval, a retry, a reassignment, a resume — never an action
  handler.
- **Saves are optimistic.** `FlowInstance`, `FlowDesign` and `FlowBundle` are `versionLock` models: of two concurrent
  saves the later fails ("Data version does not match…"). `FlowActionReplayGuard` answers a repeated approve or reject
  with the current state instead of a second vote. An instance's save updates its row by id and version without
  reading it first, and writes only the fields the state holds (`FlowExecutionStateMapper.STATE_FIELDS`, nulls
  included): a column written apart from the state — `superseded`, by `markSuperseded` — keeps its value. A new
  `FlowInstance` field the mapper writes goes into that set (`FlowExecutionStateMapperTest` checks it).
- **Approvers are found from the employee a record is about, unless a source says the submitter.**
  `ApproverResolutionService` starts an organization lookup — supervisor, department — from the record's subject
  (`_subjectId`) by default, so a record filed on someone's behalf goes where theirs would; a source starts from the
  submitter only when it says so (`relativeTo: INITIATOR`, `deptScope: INITIATOR_DEPT`). Whether a submitter who is
  also an approver is asked is the flow's `SelfApprovalStrategy` (`ApproverDedupService`); the employee, when someone
  else submitted, is asked like any other approver.
- **A submission goes to one flow.** `DefaultFlowTriggerRegistry.findSubmitFlows` returns the document's enabled
  approval flows whose condition holds, else the one without a condition — the default; `FlowLaunchServiceImpl`
  refuses none and several; `SubmitConditions` refuses two flows with the same condition, two defaults included, at a
  publish while enabled, an enable and a rollback.
- **A flow runs at most once a minute.** `FlowCron.refusal` refuses a cron whose second field is not a single value;
  publishing (`TriggerConsistencyValidator`) and `GET /flow/cron/preview` both ask it.
- **Identity is the server's.** `actorId`, `initiatorId` and `tenantId` are `@JsonProperty(READ_ONLY)` on every request
  and resolved from the context; `FlowClient` takes none of them as a parameter.
- **Bookkeeping runs past data permissions; reads are judged.** The instance store, the ledger and the projections
  ignore the caller's row scope; who may act is the action handlers' call (`ApprovalActorValidator`), who may see is
  `FlowInstanceAccessGuard`'s. The caller's own lists carry `@SkipPermissionCheck`, pinned to the caller, and a
  client's filters are ANDed onto the pin (`CallerConditions`).
- **The preview walks the engine's own code.** `ApprovalPathPreviewer` routes with `FlowGatewayRouter`, resolves with
  `PendingApprovalFactory` and auto-approves with `ApproverDedupService`, on a state never saved — a routing or
  resolution change reaches submissions and previews together.
- **Stored codes are data.** Enum codes and option items stored by the flow models — in columns and inside
  `FlowDesign.designJson`, `FlowBundle.designJson` / `compiledJson` and an instance's state — are read strictly: an
  unknown code throws. Renaming or removing one, or renaming a JSON key, ships a migration SQL in the same change, for
  the deploying application to run with its release; model it on zingkey-hcm's
  `Release-20261030/20261029-approval-center/01-before-jar-withdraw-policy.sql`. `StoredDesignJsonTest` fails the
  build when an enum change strands a stored design: answer it with the migration, never by editing its fixtures.

## Where the rules live

The softa-docs pages state each rule; this is where to change it.

| Rule | Code |
|---|---|
| Routing: edge order, default edges, the inclusive gateway's paired join | `FlowGatewayRouter`; `BranchRegions.pairOf` and `GatewayValidator` at publish; `FlowExecutionOrchestrator.creditUntakenBranches` at run time |
| Edge rules: how they are judged and checked | `FilterEvaluator`; `FieldConstraints.checkCondition` at publish |
| Who approves a step: groups, sources, accounts that can act | `ApproverResolutionService` (`resolvePeople`, also for CC rules), `PendingApprovalFactory`, `FlowOrgService` |
| When the approval mode and the empty-approver strategy apply | `ApprovalNodeConfig.mayHaveSeveralApprovers` / `findsApproversByRule`, `ApproverSourceType.isFindsOne`, `ApproverResolutionService.resolveApprovalMode`, `PendingApprovalFactory.resolveEmptyApproverStrategy` |
| Votes needed, capped by the approvers found | `ApproverResolutionService.capRequiredApprovals` |
| Why a step found nobody | `ApproverResolutionService.whyNobody` |
| What a submitter may submit or cancel | `FlowLaunchServiceImpl` (`AccessScope.callAs(UPDATE, …)`) |
| Routing variables, and numbers restored on load | `RecordVariables`, `RecordVariables.restoreNumbers` |
| A rule reading through relations, up to `RecordVariables.MAX_HOPS` levels: which paths, when read, which a flow needs | `RecordVariables.routedPathField` / `readPaths`; `CompiledFlowDefinition.rulePaths`, `DefaultFlowTriggerRegistry.submitConditionPaths`; read in `FlowLaunchServiceImpl.submitVariables`, `ApprovalFormWriteService.refreshVariables`, `FlowAutomationService.startVariables`; checked by `GatewayValidator.checkRule`; offered as `FlowVariableView.ruleFields` |
| A record node's model, and the paths its filters, orders and fields name, checked at publish | `TaskConfigValidator.checkQuery`, with softa-orm's `FieldPaths` |
| A delegation rule's status — Scheduled, Active, Ended, Cancelled — the one that routes a task, the one the lists show and the one the administrators' list filters by | `FlowDelegationStatus.of`, and `condition` for the same judgement as a filter on the stored fields (`FlowDelegationSearchTest` holds them together); routing in `FlowDelegationServiceImpl.findActiveDelegation`, the caller's lists in `searchDelegations` (`FlowDelegationBox`), the administrators' in `searchAll`, shown by `FlowDelegationViews` |
| Withdrawing and taking back a failed approval | `WithdrawPolicyGuard` |
| A task row's current steps | `CurrentStepsReader` |
| A subflow that cannot run | `FlowExecutionOrchestrator.executeSubflow` |
| Refusals answered with their reason | `FlowExceptionHandler`, ordered first (`Ordered.HIGHEST_PRECEDENCE`) |

## Testing

- Run `mvn -B -ntp test -pl starters/flow-starter`, after `mvn -B -ntp -DskipTests install -pl starters/flow-starter
  -am` when the modules it depends on are not installed.
- The engine is tested without Spring: `TestFlowRuntimeFactory` wires a `FlowRuntimeFacade` over
  `StubFlowBundleRegistry` and `StubFlowInstanceStore` (or `PersistingFlowInstanceStore`), with
  `NoopApprovalActionLedger` and `TestActorAccounts`. The `runtime/*ScenarioTest` classes drive whole approvals through
  it — the place for a behaviour test.
- `compiler/*CompileTest` cover the diagnostics, a class per family of rules; `new DefaultFlowCompiler()` runs the
  built-in validators alone.
- `StoredDesignJsonTest` reads the designs in `src/test/resources/stored-designs` with the runtime's Jackson settings;
  add a fixture when a new design shape reaches a database.
- `FlowExceptionHandlerOrderTest` keeps `FlowExceptionHandler` ahead of softa-web's catch-all.

## Documentation

The engine's behaviour is documented in the softa-docs Flow pages, sources in the softa-docs repository under
`content/zh-CN/docs/features/flow/` (the source) and `content/en-US/docs/features/flow/` (its English mirror):

| Page | English | Chinese |
|---|---|---|
| Overview: model, scenarios, triggers, node types, routing | https://softa.io/en-US/docs/features/flow | https://softa.io/zh-CN/docs/features/flow |
| Approval flows: states, settings, approvers, actions, errors, notifications | https://softa.io/en-US/docs/features/flow/approval | https://softa.io/zh-CN/docs/features/flow/approval |
| Automation: starts, sync and async, waits, tasks, messaging, cron | https://softa.io/en-US/docs/features/flow/automation | https://softa.io/zh-CN/docs/features/flow/automation |
| Integration: approving business records, seeds, checklist, configuration, extension points | https://softa.io/en-US/docs/features/flow/integration | https://softa.io/zh-CN/docs/features/flow/integration |
| API and data: REST APIs, persistence, descriptors | https://softa.io/en-US/docs/features/flow/api | https://softa.io/zh-CN/docs/features/flow/api |
| Design notes | https://softa.io/en-US/docs/features/flow/design | https://softa.io/zh-CN/docs/features/flow/design |
| The designer (frontend) | https://softa.io/en-US/docs/frontend_dev/components/flow | https://softa.io/zh-CN/docs/frontend_dev/components/flow |

**A behaviour change updates the softa-docs Flow pages in the same change, zh-CN first, then the en-US mirror. This
README changes only when what it lists changes — the wiring and configuration, the extension points and their
contracts, the invariants.**
