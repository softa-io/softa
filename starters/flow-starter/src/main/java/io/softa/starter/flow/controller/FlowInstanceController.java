package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.orm.annotation.DataMask;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.dto.FlowBatchActionResult;
import io.softa.starter.flow.dto.FlowInstanceOverlay;
import io.softa.starter.flow.dto.FlowInstanceSearchRequest;
import io.softa.starter.flow.dto.FlowInstanceSummaryView;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.dto.FlowTraceEntryView;
import io.softa.starter.flow.dto.FlowUrgeStatus;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.FormPermissionService;
import io.softa.starter.flow.runtime.engine.BatchApprovalExecutor;
import io.softa.starter.flow.runtime.engine.UrgeThrottle;
import io.softa.starter.flow.runtime.engine.WithdrawPolicyGuard;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.FlowExecutionTraceService;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.FlowLaunchService;
import io.softa.starter.flow.service.query.FlowInstanceSummaryComposer;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * Runtime flow instances: launch, the caller's own instance search, per-instance reads (state,
 * canvas overlay, trace, node form permissions), a record's approvals and the preview of its next
 * one, and the approval actions. Every endpoint acts as
 * the authenticated user. Trigger evaluation lives on {@link FlowAutomationController},
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
                                  FlowUserNames names) {
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
                    + "for the full event list. The initiator comes named.")
    public ApiResponse<FlowExecutionState> getInstance(@PathVariable String instanceId,
                                                       @RequestParam(defaultValue = "false") boolean includeTrace) {
        // Trace-free load by default; the full history is only fetched when asked for.
        return (includeTrace ? runtimeEngine.getInstanceWithTrace(instanceId) : runtimeEngine.getInstance(instanceId))
                .map(state -> {
                    accessGuard.requireInstanceViewer(state, currentUserId());
                    if (state.getInitiatorId() != null) {
                        state.setInitiatorName(names.namesOf(List.of(state.getInitiatorId())).get(state.getInitiatorId()));
                    }
                    return state;
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
                    + "every user named. For the record page's approval panel and the inbox task detail. "
                    + "Allowed to whoever took part in the record's approvals or can read the record.")
    public ApiResponse<FlowRecordApprovalsView> getRecordApprovals(@RequestParam String modelName,
                                                                  @RequestParam String rowId) {
        String requesterId = currentUserId();
        return ApiResponse.success(instanceService.recordApprovals(modelName, rowId,
                history -> accessGuard.requireRecordViewer(modelName, rowId, history, requesterId)));
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
                    + "read the record with their own permissions.")
    public ApiResponse<FlowApprovalPreview> previewRecordApproval(@RequestParam String modelName,
                                                                 @RequestParam String rowId) {
        return ApiResponse.success(launchService.previewRecord(modelName, rowId, currentUserId()));
    }

    @GetMapping("/{instanceId}/record")
    @Operation(summary = "Get the record an instance approves",
            description = "The business record a record's approval runs on, for its initiator and the people it "
                    + "gives a task to: read past their own row permissions. Any other instance is refused. Every "
                    + "field, dynamic ones included, but the sensitive ones the caller may see on no row; the field "
                    + "permissions of the waiting steps the caller approves are laid over that — what they list as "
                    + "Readonly, Editable or Required is shown, what they mark Hidden is not. Callers with no "
                    + "waiting step besides the initiator also miss what any approval step hides. Relations and "
                    + "options come as references, and masked fields are masked as on every model read.")
    @DataMask
    public ApiResponse<Map<String, Object>> getInstanceRecord(@PathVariable String instanceId) {
        return runtimeEngine.getInstance(instanceId)
                .map(state -> {
                    String viewerId = currentUserId();
                    accessGuard.requireRecordGrantee(state, viewerId);
                    return instanceService.recordOf(state, viewerId)
                            .map(ApiResponse::success)
                            .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                                    "The record of flow instance " + instanceId + " does not exist"));
                })
                .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                        "Flow instance not found: " + instanceId));
    }

    @PostMapping("/search")
    @Operation(summary = "Search my flow instances",
            description = "Paged summaries of the caller's own instances — the initiator filter is always "
                    + "the authenticated user (any requested initiatorId is overridden). Heavy JSON state "
                    + "columns and the trace are excluded; fetch a single instance or its overlay for detail. "
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
        return ApiResponse.success(runtimeEngine.approve(request));
    }

    @PostMapping("/reject")
    @Operation(summary = "Reject pending node", description = "Rejects a pending approval node and marks the flow as rejected.")
    public ApiResponse<FlowExecutionState> reject(@Valid @RequestBody FlowRejectRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.reject(request));
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
        return ApiResponse.success(runtimeEngine.transfer(request));
    }

    @PostMapping("/delegate")
    @Operation(summary = "Delegate pending approval task", description = "Delegates a pending approval task from the current actor to another actor on the same node and cycle.")
    public ApiResponse<FlowExecutionState> delegate(@Valid @RequestBody FlowDelegateRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.delegate(request));
    }

    @PostMapping("/add-sign-before")
    @Operation(summary = "Add prerequisite signer before current approver", description = "Adds a prerequisite signer before the current approver on the same approval node and cycle.")
    public ApiResponse<FlowExecutionState> addSignBefore(@Valid @RequestBody FlowAddSignBeforeRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.addSignBefore(request));
    }

    @PostMapping("/add-sign-after")
    @Operation(summary = "Add follow-up signer after current approver", description = "Adds a follow-up signer after the current approver on the same approval node and cycle.")
    public ApiResponse<FlowExecutionState> addSignAfter(@Valid @RequestBody FlowAddSignAfterRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.addSignAfter(request));
    }

    @PostMapping("/cc")
    @Operation(summary = "CC people on a pending approval", description = "The caller, an approver of the pending "
            + "step still to vote, CCs one or more people (`targetActorIds`): each gets a read-only copy of the approval "
            + "and an inbox notice, without changing who approves. Approvers of the step and people already CC'd on it "
            + "this cycle are skipped; refused when nobody is left.")
    public ApiResponse<FlowExecutionState> cc(@Valid @RequestBody FlowCcRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.cc(request));
    }

    @PostMapping("/cc/read")
    @Operation(summary = "Acknowledge a CC as read", description = "Allows the CC recipient to confirm the CC task as read without affecting approval ownership or flow progression.")
    public ApiResponse<FlowExecutionState> readCc(@Valid @RequestBody FlowCcReadRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.readCc(request));
    }

    @PostMapping("/return")
    @Operation(summary = "Return pending approval", description = "Returns a pending approval to the initiator, which ends the instance as Returned. The initiator edits and submits again, which starts a new instance.")
    public ApiResponse<FlowExecutionState> returnApproval(@Valid @RequestBody FlowReturnRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.returnApproval(request));
    }

    @PostMapping("/withdraw")
    @Operation(summary = "Withdraw approval flow", description = "The initiator takes back an approval waiting on "
            + "its approvers, within the flow's withdraw policy: Never, BeforeFirstAction (until an approver first "
            + "acts) or UntilCompleted.")
    public ApiResponse<FlowExecutionState> withdraw(@Valid @RequestBody FlowWithdrawRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.withdraw(request));
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
                    + "flow's withdraw policy still permits it — exactly when a withdrawal would be accepted. "
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
}
