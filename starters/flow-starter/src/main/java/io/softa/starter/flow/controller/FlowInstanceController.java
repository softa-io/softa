package io.softa.starter.flow.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.orm.annotation.DataMask;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.*;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.bundle.FlowBundleViews;
import io.softa.starter.flow.runtime.engine.*;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.FlowBundleService;
import io.softa.starter.flow.service.FlowExecutionTraceService;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.FlowLaunchService;
import io.softa.starter.flow.service.query.FlowInstanceSummaryComposer;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard.RecordAdmission;
import io.softa.starter.flow.service.support.FlowUserNames;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * Runtime flow instances: launch, the caller's own instance search, per-instance reads (state, summary,
 * canvas overlay, trace, the revision it runs, node form permissions, the record it approves), a
 * record's approvals, the record read through them and the preview of its next one, and the approval
 * actions. Every endpoint acts as
 * the authenticated user. The per-instance reads are ruled on by {@link FlowInstanceAccessGuard}, not by
 * the caller's permissions on the flow models, so an approval page reads everything it shows here.
 * Trigger evaluation lives on {@link FlowAutomationController},
 * published-revision reads on {@code FlowBundleController}; engine-internal callbacks are handled
 * in-process by consumers.
 */
@Tag(name = "Flow Instances")
@Validated
@RestController
@RequestMapping("/flow/instances")
public class FlowInstanceController {

    private final FlowRuntimeEngine runtimeEngine;
    private final FormPermissionService formPermissionService;
    private final FlowInstanceService instanceService;
    private final FlowExecutionTraceService traceService;
    private final FlowInstanceAccessGuard accessGuard;
    private final FlowLaunchService launchService;
    private final UrgeThrottle urgeThrottle;
    private final WithdrawPolicyGuard withdrawPolicyGuard;
    private final BatchApprovalExecutor batchExecutor;
    private final FlowInstanceSummaryComposer summaryComposer;
    private final FlowUserNames names;
    private final FlowBundleService bundleService;

    public FlowInstanceController(FlowRuntimeEngine runtimeEngine,
                                  FormPermissionService formPermissionService,
                                  FlowInstanceService instanceService,
                                  FlowExecutionTraceService traceService,
                                  FlowInstanceAccessGuard accessGuard,
                                  FlowLaunchService launchService,
                                  UrgeThrottle urgeThrottle,
                                  WithdrawPolicyGuard withdrawPolicyGuard,
                                  BatchApprovalExecutor batchExecutor,
                                  FlowInstanceSummaryComposer summaryComposer,
                                  FlowUserNames names,
                                  FlowBundleService bundleService) {
        this.runtimeEngine = runtimeEngine;
        this.formPermissionService = formPermissionService;
        this.instanceService = instanceService;
        this.traceService = traceService;
        this.accessGuard = accessGuard;
        this.launchService = launchService;
        this.urgeThrottle = urgeThrottle;
        this.withdrawPolicyGuard = withdrawPolicyGuard;
        this.batchExecutor = batchExecutor;
        this.summaryComposer = summaryComposer;
        this.names = names;
        this.bundleService = bundleService;
    }

    @PostMapping("/start")
    @Operation(summary = "Start flow instance", description = "Starts an Automation flow whose trigger is Api, "
            + "by its id or a pinned bundle. Any other flow is refused: its trigger names another way to start it.")
    public ApiResponse<FlowExecutionState> start(@Valid @RequestBody FlowStartRequest request) {
        request.setInitiatorId(currentUserId());
        return ApiResponse.success(runtimeEngine.startAutomation(request));
    }

    @GetMapping("/{instanceId}")
    @Operation(summary = "Get flow instance",
            description = "Gets the current runtime state of a flow instance. The trace is excluded "
                    + "by default (poll it incrementally via the trace endpoint); pass includeTrace=true "
                    + "for the full event list. The initiator comes named. The record's values — inputPayload, "
                    + "variables, returnData — are left out, here and in every approval action's answer: the "
                    + "record is read through /record, with the field permissions of the caller's step.")
    public ApiResponse<FlowExecutionState> getInstance(@PathVariable String instanceId,
                                                       @RequestParam(defaultValue = "false") boolean includeTrace) {
        // Trace-free load by default; the full history is only fetched when asked for.
        return (includeTrace ? runtimeEngine.getInstanceWithTrace(instanceId) : runtimeEngine.getInstance(instanceId))
                .map(state -> {
                    accessGuard.requireInstanceViewer(state, currentUserId());
                    if (state.getInitiatorId() != null) {
                        state.setInitiatorName(names.namesOf(List.of(state.getInitiatorId())).get(state.getInitiatorId()));
                    }
                    return withoutRecordValues(state);
                })
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                        "Flow instance not found: " + instanceId));
    }

    @GetMapping("/{instanceId}/overlay")
    @Operation(summary = "Get execution overlay",
            description = "Per-node run state derived server-side for painting the canvas: completed / "
                    + "waiting / failed per node id, with timestamps and pending-approval details.")
    public ApiResponse<FlowInstanceOverlay> getOverlay(@PathVariable String instanceId) {
        return runtimeEngine.getInstanceWithTrace(instanceId)
                .map(state -> {
                    accessGuard.requireInstanceViewer(state, currentUserId());
                    return FlowInstanceOverlay.of(state);
                })
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                        "Flow instance not found: " + instanceId));
    }

    @GetMapping("/{instanceId}/trace")
    @Operation(summary = "Get execution trace (incremental)",
            description = "Trace rows ordered by sequence. Pass sinceSequence to fetch only rows the "
                    + "client has not seen yet — the polling loop for a running instance.")
    public ApiResponse<List<FlowTraceEntryView>> getTrace(@PathVariable String instanceId,
                                                          @RequestParam(defaultValue = "-1") long sinceSequence) {
        return runtimeEngine.getInstance(instanceId)
                .map(state -> {
                    accessGuard.requireInstanceViewer(state, currentUserId());
                    // Allowed to see the instance, so its trace — read past the row scope the guard just ruled on.
                    List<FlowTraceEntryView> entries = ContextUtils.withoutPermissionChecks(
                                    () -> traceService.findByInstanceIdSince(instanceId, sinceSequence)).stream()
                            .map(row -> new FlowTraceEntryView(row.getSequence(), row.getNodeId(), row.getFlowNodeType(),
                                    row.getEventType(), row.getEventTime(), row.getMessage()))
                            .toList();
                    return ApiResponse.success(entries);
                })
                .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                        "Flow instance not found: " + instanceId));
    }

    @GetMapping("/by-record")
    @Operation(summary = "Get a record's approval history",
            description = "The approval instances of a business record, newest first: status, initiator, "
                    + "the steps still waiting with the approvers yet to act, and the approval actions, "
                    + "every user named; each says whether the caller may open it (viewable) — its detail, "
                    + "progress diagram and trace. For the record page's approval panel and the inbox task detail. "
                    + "Allowed to whoever took part in the record's approvals or can read the record.")
    public ApiResponse<FlowRecordApprovalsView> getRecordApprovals(@RequestParam String modelName,
                                                                  @RequestParam String rowId) {
        String requesterId = currentUserId();
        return ApiResponse.success(instanceService.recordApprovals(modelName, rowId, history -> {
            accessGuard.requireRecordViewer(modelName, rowId, history, requesterId);
            accessGuard.markViewable(modelName, rowId, history, requesterId);
        }));
    }

    @GetMapping("/preview")
    @Operation(summary = "Preview a record's approval",
            description = "What submitting the business record would do now, without submitting it: the Submit flow "
                    + "it would get and the approval steps it would go through, every step assumed approved — each "
                    + "with the approvers who would be asked, or why it passes on its own (the submitter is its "
                    + "approver, an approver already approved an earlier step, nobody resolves to it). A refusal "
                    + "by the record's own checks, an approval already in progress, no applicable flow or several "
                    + "are reported, not raised; a route that depends on data only known once the approval runs is "
                    + "reported as undecided, never guessed. Nothing is started or written. Allowed to whoever can "
                    + "read the record with their own permissions and, while its approval is in progress, to those "
                    + "who take part in that approval.")
    public ApiResponse<FlowApprovalPreview> previewRecordApproval(@RequestParam String modelName,
                                                                 @RequestParam String rowId) {
        return ApiResponse.success(launchService.previewRecord(modelName, rowId, currentUserId()));
    }

    @GetMapping("/{instanceId}/record")
    @Operation(summary = "Get the record an instance approves",
            description = "The business record a record's approval runs on, as { record, fields }. Any other "
                    + "instance is refused. Its initiator and the people it gives or gave a task to read it past their "
                    + "own row permissions: every field, dynamic ones included, but the sensitive ones the caller may "
                    + "see on no row; the field permissions of the waiting steps the caller approves are laid over "
                    + "that — what they list as Readonly, Editable or Required is shown, what they mark Hidden is "
                    + "not. Callers with no waiting step besides the initiator also miss what any approval step "
                    + "hides. A one-to-many's rows lose the sensitive fields of their own model the caller may see "
                    + "on no row, unless a waiting step of the caller lists the one-to-many as Readonly, Editable "
                    + "or Required: then they come whole. fields holds, by model, the definitions of the fields "
                    + "shown that the caller's own metadata leaves out, as the metadata API serves a field. Anyone "
                    + "else who can read the record with their own permissions gets it through the model's "
                    + "ordinary read, with no fields. Relations and options come as references, and masked fields "
                    + "are masked as on every model read.")
    @DataMask
    public ApiResponse<FlowInstanceRecordView> getInstanceRecord(@PathVariable String instanceId) {
        return runtimeEngine.getInstance(instanceId)
                .map(state -> {
                    String viewerId = currentUserId();
                    Optional<FlowInstanceRecordView> record = switch (accessGuard.requireRecordAccess(state, viewerId)) {
                        case GRANTED -> instanceService.recordOf(state, viewerId);
                        case OWN_PERMISSIONS -> instanceService.recordThroughOwnPermissions(state.getModelName(),
                                state.getRowId());
                    };
                    return record
                            .map(ApiResponse::success)
                            .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                                    "The record of flow instance " + instanceId + " does not exist"));
                })
                .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                        "Flow instance not found: " + instanceId));
    }

    @GetMapping("/by-record/record")
    @Operation(summary = "Get a record through its approvals",
            description = "The business record, as GET /flow/instances/{instanceId}/record returns it ({ record, "
                    + "fields }), whichever of its approvals is current: for a page keyed by the record that shows "
                    + "them all as its history. The newest of the record's approvals in which the caller took part — "
                    + "as its initiator or a person it gives or gave a task to — lends it to them as that instance "
                    + "would: an approver it waits on sees the record as their step shows it, someone who took part "
                    + "only in an earlier submission as that one shows it to a participant with no waiting step. "
                    + "Anyone else who can read the record with their own permissions gets it through the model's "
                    + "ordinary read, with no fields — the only way a record never submitted is read here; everyone "
                    + "else is refused. A model that does not exist is not found, and so is a record deleted under "
                    + "someone who took part in its approvals; to anyone else, a missing record is refused like one "
                    + "they cannot read. Relations and options come as references, and masked fields are masked as on "
                    + "every model read.")
    @DataMask
    public ApiResponse<FlowInstanceRecordView> getRecordThroughApprovals(@RequestParam String modelName,
                                                                         @RequestParam String rowId) {
        if (!ModelManager.existModel(modelName)) {
            return ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND, "Model not found: " + modelName);
        }
        String viewerId = currentUserId();
        RecordAdmission admission = accessGuard.requireRecordAccess(modelName, rowId, viewerId);
        Optional<FlowInstanceRecordView> record = switch (admission.access()) {
            case GRANTED -> instanceService.recordOf(admission.approval(), viewerId);
            case OWN_PERMISSIONS -> instanceService.recordThroughOwnPermissions(modelName, rowId);
        };
        return record
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                        "The " + modelName + " record " + rowId + " does not exist"));
    }

    @GetMapping("/{instanceId}/summary")
    @Operation(summary = "Get an instance's summary",
            description = "The instance as POST /flow/instances/search composes its rows: the steps it waits on "
                    + "with the approvers still to act, what a record's approval kept of its record (subject, "
                    + "department, effective date, record code), whether it awaits revision, whether its record was "
                    + "deleted or cancelled and where an approved change stands, every user and department named; on "
                    + "the caller's own approval waiting on its approvers, whether they may withdraw or urge it now. "
                    + "Allowed to whoever may view the instance.")
    public ApiResponse<FlowInstanceSummaryView> getInstanceSummary(@PathVariable String instanceId) {
        return runtimeEngine.getInstance(instanceId)
                .flatMap(state -> {
                    String userId = currentUserId();
                    accessGuard.requireInstanceViewer(state, userId);
                    // Allowed to see the instance, so its row — composed past the row scope the guard just ruled
                    // on, as the caller's own search composes its rows.
                    return ContextUtils.withoutPermissionChecks(() -> summaryComposer
                            .summarize(instanceService.findByInstanceIds(List.of(instanceId)), userId)
                            .stream()
                            .findFirst());
                })
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                        "Flow instance not found: " + instanceId));
    }

    @GetMapping("/{instanceId}/design")
    @Operation(summary = "Get the design an instance runs",
            description = "The revision the instance runs on, as GET /flow/bundles/{bundleId}?include=design returns "
                    + "it: its summary and the design snapshot taken at publish, to draw the instance's diagram. "
                    + "Allowed to whoever may view the instance, whatever their own permissions on the flow's "
                    + "revisions.")
    public ApiResponse<FlowBundleDetailView> getInstanceDesign(@PathVariable String instanceId) {
        return runtimeEngine.getInstance(instanceId)
                .map(state -> {
                    accessGuard.requireInstanceViewer(state, currentUserId());
                    Long bundleId = state.getBundleId();
                    // Allowed to see the instance, so the revision it runs — read past the caller's permissions on
                    // the flow's revisions, which an approver seldom holds.
                    Optional<FlowBundle> bundle = bundleId == null ? Optional.empty()
                            : ContextUtils.withoutPermissionChecks(() -> bundleService.findById(bundleId));
                    return bundle
                            .map(entity -> ApiResponse.success(FlowBundleViews.detail(entity, true)))
                            .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                                    "Flow bundle not found: " + bundleId));
                })
                .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                        "Flow instance not found: " + instanceId));
    }

    @PostMapping("/search")
    @Operation(summary = "Search my flow instances",
            description = "Paged summaries of the caller's own instances — the initiator filter is always "
                    + "the authenticated user (any requested initiatorId is overridden), and the request's filters "
                    + "and orders, on stored fields of FlowInstance only, narrow and sort within them. Heavy JSON "
                    + "state columns and the trace are excluded; fetch a single instance or its overlay for detail. "
                    + "Cross-initiator monitoring lives on POST /flow/monitor/instances/search.")
    // Pinned to the caller, so row scope could only empty it: FlowInstance is granted to no business role.
    @SkipPermissionCheck
    public ApiResponse<Page<FlowInstanceSummaryView>> searchInstances(@RequestBody FlowInstanceSearchRequest request) {
        return ApiResponse.success(summaryComposer.summarize(instanceService.searchSummaries(request, currentUserId()), currentUserId()));
    }

    @PostMapping("/approve")
    @Operation(summary = "Approve pending node", description = "Approves a pending approval node and resumes execution.")
    public ApiResponse<FlowExecutionState> approve(@Valid @RequestBody FlowApproveRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(withoutRecordValues(runtimeEngine.approve(request)));
    }

    @PostMapping("/reject")
    @Operation(summary = "Reject pending node", description = "Rejects a pending approval node and marks the flow as rejected.")
    public ApiResponse<FlowExecutionState> reject(@Valid @RequestBody FlowRejectRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(withoutRecordValues(runtimeEngine.reject(request)));
    }

    @PostMapping("/batch/approve")
    @Operation(summary = "Approve pending tasks in a batch",
            description = "Approves each of the caller's pending tasks named, each on its own: one refused task changes "
                    + "nothing for the others. A task whose step has fields to fill in is skipped. Returns each "
                    + "task's outcome.")
    public ApiResponse<FlowBatchActionResult> batchApprove(@Valid @RequestBody FlowBatchActionRequest request) {
        return ApiResponse.success(batchExecutor.execute(BatchApprovalExecutor.Action.APPROVE, request, currentUserId()));
    }

    @PostMapping("/batch/return")
    @Operation(summary = "Return pending tasks in a batch",
            description = "Returns each of the caller's pending tasks named to its initiator, each on its own, with one "
                    + "comment (required). A task whose step allows no return or has fields to fill in is skipped. "
                    + "Returns each task's outcome.")
    public ApiResponse<FlowBatchActionResult> batchReturn(@Valid @RequestBody FlowBatchActionRequest request) {
        return ApiResponse.success(batchExecutor.execute(BatchApprovalExecutor.Action.RETURN, request, currentUserId()));
    }

    @PostMapping("/batch/reject")
    @Operation(summary = "Reject pending tasks in a batch",
            description = "Rejects each of the caller's pending tasks named, each on its own, with one comment "
                    + "(required). A task whose step has fields to fill in is skipped. Returns each task's "
                    + "outcome.")
    public ApiResponse<FlowBatchActionResult> batchReject(@Valid @RequestBody FlowBatchActionRequest request) {
        return ApiResponse.success(batchExecutor.execute(BatchApprovalExecutor.Action.REJECT, request, currentUserId()));
    }

    @PostMapping("/transfer")
    @Operation(summary = "Transfer pending approval task", description = "Transfers a pending approval task from the current actor to another actor on the same node and cycle.")
    public ApiResponse<FlowExecutionState> transfer(@Valid @RequestBody FlowTransferRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(withoutRecordValues(runtimeEngine.transfer(request)));
    }

    @PostMapping("/delegate")
    @Operation(summary = "Delegate pending approval task", description = "Delegates a pending approval task from the current actor to another actor on the same node and cycle.")
    public ApiResponse<FlowExecutionState> delegate(@Valid @RequestBody FlowDelegateRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(withoutRecordValues(runtimeEngine.delegate(request)));
    }

    @PostMapping("/cc")
    @Operation(summary = "CC people on a pending approval", description = "The caller, an approver of the pending "
            + "step still to vote, CCs one or more people (`targetActorIds`): each gets a read-only copy of the approval "
            + "and an inbox notice, without changing who approves. Approvers of the step and people already CC'd on it "
            + "this cycle are skipped; refused when nobody is left.")
    public ApiResponse<FlowExecutionState> cc(@Valid @RequestBody FlowCcRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(withoutRecordValues(runtimeEngine.cc(request)));
    }

    @PostMapping("/cc/read")
    @Operation(summary = "Acknowledge a CC as read", description = "Allows the CC recipient to confirm the CC task as read without affecting approval ownership or flow progression.")
    public ApiResponse<FlowExecutionState> readCc(@Valid @RequestBody FlowCcReadRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(withoutRecordValues(runtimeEngine.readCc(request)));
    }

    @PostMapping("/return")
    @Operation(summary = "Return pending approval", description = "Returns a pending approval to the initiator, which ends the instance as Returned. The initiator edits and submits again, which starts a new instance.")
    public ApiResponse<FlowExecutionState> returnApproval(@Valid @RequestBody FlowReturnRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(withoutRecordValues(runtimeEngine.returnApproval(request)));
    }

    @PostMapping("/withdraw")
    @Operation(summary = "Withdraw approval flow", description = "The initiator takes back an approval waiting on "
            + "its approvers, within the flow's withdraw policy: Never, BeforeFirstAction (until an approver first "
            + "acts) or UntilCompleted. A record's approval that failed is taken back whatever the policy, by its "
            + "submitter, while the record still waits on it: the record becomes a draft again, the failed instance "
            + "stays as it is.")
    public ApiResponse<FlowExecutionState> withdraw(@Valid @RequestBody FlowWithdrawRequest request) {
        request.setActorId(currentUserId());
        // A record's approval that failed waits on nobody: its submitter takes the record back, as from the record.
        Optional<FlowExecutionState> failed = ContextUtils.withoutPermissionChecks(
                () -> runtimeEngine.getInstance(request.getInstanceId()))
                .filter(state -> withdrawPolicyGuard.takesBack(state, request.getActorId()));
        if (failed.isPresent()) {
            FlowExecutionState state = failed.get();
            return ApiResponse.success(withoutRecordValues(launchService.withdrawRecord(state.getModelName(),
                    state.getRowId(), request.getActorId(), request.getComment())));
        }
        return ApiResponse.success(withoutRecordValues(runtimeEngine.withdraw(request)));
    }

    @PostMapping("/urge")
    @Operation(summary = "Urge pending approvers",
            description = "The initiator reminds the approvers the approval waits on, on a flow that lets them "
                    + "(urgeEnabled, default on). A step urged within the flow's interval (urgeIntervalMinutes, "
                    + "default 240) — by the initiator or an administrator — is not urged again, and when every step "
                    + "is, the urge is refused. Returns when the approval may be urged next.")
    public ApiResponse<FlowUrgeStatus> urge(@Valid @RequestBody FlowUrgeRequest request) {
        request.setActorId(currentUserId());
        FlowExecutionState state = runtimeEngine.urge(request);
        return ApiResponse.success(urgeThrottle.status(state, request.getActorId(), LocalDateTime.now()));
    }

    @GetMapping("/{instanceId}/urge")
    @Operation(summary = "Get urge status",
            description = "Whether the approval may be urged now, and if not, when it may be — never, for its "
                    + "initiator, on a flow that does not let its initiator urge. Allowed to whoever may view the "
                    + "instance.")
    public ApiResponse<FlowUrgeStatus> getUrgeStatus(@PathVariable String instanceId) {
        return runtimeEngine.getInstance(instanceId)
                .map(state -> {
                    String userId = currentUserId();
                    accessGuard.requireInstanceViewer(state, userId);
                    return ApiResponse.success(urgeThrottle.status(state, userId, LocalDateTime.now()));
                })
                .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                        "Flow instance not found: " + instanceId));
    }

    @GetMapping("/{instanceId}/withdraw")
    @Operation(summary = "Get whether the caller may withdraw",
            description = "True when the caller is the initiator of an approval waiting on its approvers and the "
                    + "flow's withdraw policy still permits it, or the submitter of a record whose approval failed "
                    + "while the record still waits on it — exactly when a withdrawal would be accepted. "
                    + "Allowed to whoever may view the instance.")
    public ApiResponse<Boolean> getWithdrawable(@PathVariable String instanceId) {
        return runtimeEngine.getInstance(instanceId)
                .map(state -> {
                    String userId = currentUserId();
                    accessGuard.requireInstanceViewer(state, userId);
                    return ApiResponse.success(withdrawPolicyGuard.withdrawable(state, userId));
                })
                .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                        "Flow instance not found: " + instanceId));
    }

    @PostMapping("/comment")
    @Operation(summary = "Add comment to flow instance",
            description = "Adds a comment to a running flow instance without changing its status, recorded in its "
                    + "approval history. Only its participants comment: the initiator and the people it gives or "
                    + "gave a task to.")
    public ApiResponse<Boolean> addComment(@Valid @RequestBody FlowCommentRequest request) {
        request.setActorId(currentUserId());
        runtimeEngine.addComment(request);
        return ApiResponse.success(true);
    }

    @GetMapping("/{instanceId}/nodes/{nodeId}/formPermissions")
    @Operation(summary = "Get form permissions", description = "Returns field-level form permissions configured for a specific approval node.")
    public ApiResponse<Map<String, FormFieldPermission>> getFormPermissions(
            @PathVariable String instanceId, @PathVariable String nodeId) {
        return runtimeEngine.getInstance(instanceId)
                .map(state -> {
                    accessGuard.requireInstanceViewer(state, currentUserId());
                    return ApiResponse.success(formPermissionService.getFieldPermissions(instanceId, nodeId));
                })
                .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                        "Flow instance not found: " + instanceId));
    }

    /**
     * The state as an approval's participants read it — the instance, and every action's answer: without the record's
     * values. The start payload and the variables hold the record's fields as submitted and what later steps worked
     * out, past the field permissions the record's own read applies ({@code /record}, which leaves out what the
     * caller's step hides), so a field a step hid could be read here (zingkey-hcm#2402); an automation's result goes
     * with them. Nothing that shows an approval reads them from the state. A copy: the engine's state is left as it
     * is.
     */
    private static FlowExecutionState withoutRecordValues(FlowExecutionState state) {
        return state.toBuilder().inputPayload(null).variables(null).returnData(null).build();
    }
}
