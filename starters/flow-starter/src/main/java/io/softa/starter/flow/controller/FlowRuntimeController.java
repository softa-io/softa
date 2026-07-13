package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowInstanceOverlay;
import io.softa.starter.flow.dto.FlowInstanceSearchRequest;
import io.softa.starter.flow.dto.FlowTraceEntryView;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.FormPermissionService;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.trigger.FlowAutomationService;
import io.softa.starter.flow.runtime.trigger.FlowTriggerEvent;
import io.softa.starter.flow.runtime.trigger.FlowTriggerResult;
import io.softa.starter.flow.service.FlowExecutionTraceService;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;

/**
 * Runtime endpoints for the in-memory flow engine: instance launch, approval
 * actions, and trigger evaluation. Engine-internal callbacks are handled
 * in-process by consumers; published-revision reads live on {@code FlowBundleController}.
 */
@Tag(name = "Flow Runtime")
@Validated
@RestController
@RequestMapping("/flow/runtime")
public class FlowRuntimeController {

    /** Instance search projection — the heavy JSON state columns are deliberately excluded. */
    private static final List<String> INSTANCE_SUMMARY_FIELDS = List.of(
            LambdaUtils.getAttributeName(FlowInstance::getId),
            LambdaUtils.getAttributeName(FlowInstance::getInstanceId),
            LambdaUtils.getAttributeName(FlowInstance::getBundleId),
            LambdaUtils.getAttributeName(FlowInstance::getDesignId),
            LambdaUtils.getAttributeName(FlowInstance::getFlowCode),
            LambdaUtils.getAttributeName(FlowInstance::getFlowRevision),
            LambdaUtils.getAttributeName(FlowInstance::getTitle),
            LambdaUtils.getAttributeName(FlowInstance::getModelName),
            LambdaUtils.getAttributeName(FlowInstance::getRowId),
            LambdaUtils.getAttributeName(FlowInstance::getInitiatorId),
            LambdaUtils.getAttributeName(FlowInstance::getStatus),
            LambdaUtils.getAttributeName(FlowInstance::getFailedNodeId),
            LambdaUtils.getAttributeName(FlowInstance::getNextFireAt),
            LambdaUtils.getAttributeName(FlowInstance::getResubmissionCount),
            LambdaUtils.getAttributeName(FlowInstance::getCreatedTime),
            LambdaUtils.getAttributeName(FlowInstance::getUpdatedTime));

    private final FlowRuntimeEngine runtimeEngine;
    private final FlowAutomationService flowAutomationService;
    private final FormPermissionService formPermissionService;
    private final FlowInstanceService instanceService;
    private final FlowExecutionTraceService traceService;
    private final FlowInstanceAccessGuard accessGuard;

    public FlowRuntimeController(FlowRuntimeEngine runtimeEngine,
                                 FlowAutomationService flowAutomationService,
                                 FormPermissionService formPermissionService,
                                 FlowInstanceService instanceService,
                                 FlowExecutionTraceService traceService,
                                 FlowInstanceAccessGuard accessGuard) {
        this.runtimeEngine = runtimeEngine;
        this.flowAutomationService = flowAutomationService;
        this.formPermissionService = formPermissionService;
        this.instanceService = instanceService;
        this.traceService = traceService;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/instances/start")
    @Operation(summary = "Start flow instance", description = "Starts a runtime flow execution from a registered bundle.")
    public ApiResponse<FlowExecutionState> start(@Valid @RequestBody FlowStartRequest request) {
        request.setInitiatorId(currentUserId());
        return ApiResponse.success(runtimeEngine.start(request));
    }

    @GetMapping("/instances/{instanceId}")
    @Operation(summary = "Get flow instance",
            description = "Gets the current runtime state of a flow instance. The trace is excluded "
                    + "by default (poll it incrementally via the trace endpoint); pass includeTrace=true "
                    + "for the full event list.")
    public ApiResponse<FlowExecutionState> getInstance(@PathVariable String instanceId,
                                                       @RequestParam(defaultValue = "false") boolean includeTrace) {
        // Trace-free load by default; the full history is only fetched when asked for.
        return (includeTrace ? runtimeEngine.getInstanceWithTrace(instanceId) : runtimeEngine.getInstance(instanceId))
                .map(state -> {
                    accessGuard.requireInstanceViewer(state, currentUserId());
                    return state;
                })
                .map(ApiResponse::success)
                .orElseGet(() -> new ApiResponse<>(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                        "Flow instance not found: " + instanceId, null));
    }

    @GetMapping("/instances/{instanceId}/overlay")
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
                .orElseGet(() -> new ApiResponse<>(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                        "Flow instance not found: " + instanceId, null));
    }

    @GetMapping("/instances/{instanceId}/trace")
    @Operation(summary = "Get execution trace (incremental)",
            description = "Trace rows ordered by sequence. Pass sinceSequence to fetch only rows the "
                    + "client has not seen yet — the polling loop for a running instance.")
    public ApiResponse<List<FlowTraceEntryView>> getTrace(@PathVariable String instanceId,
                                                          @RequestParam(defaultValue = "-1") long sinceSequence) {
        return runtimeEngine.getInstance(instanceId)
                .map(state -> {
                    accessGuard.requireInstanceViewer(state, currentUserId());
                    List<FlowTraceEntryView> entries = traceService.findByInstanceIdSince(instanceId, sinceSequence).stream()
                            .map(row -> new FlowTraceEntryView(row.getSequence(), row.getNodeId(), row.getFlowNodeType(),
                                    row.getEventType(), row.getEventTime(), row.getMessage()))
                            .toList();
                    return ApiResponse.success(entries);
                })
                .orElseGet(() -> new ApiResponse<>(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                        "Flow instance not found: " + instanceId, null));
    }

    @PostMapping("/instances/search")
    @Operation(summary = "Search flow instances",
            description = "Paged instance summaries for monitoring views — heavy JSON state columns and "
                    + "the trace are excluded; fetch a single instance or its overlay for detail.")
    public ApiResponse<Page<FlowInstance>> searchInstances(@RequestBody FlowInstanceSearchRequest request) {
        Filters filters = new Filters();
        if (request.flowCode() != null) {
            filters.eq(FlowInstance::getFlowCode, request.flowCode());
        }
        if (request.designId() != null) {
            filters.eq(FlowInstance::getDesignId, request.designId());
        }
        if (request.status() != null) {
            filters.eq(FlowInstance::getStatus, request.status());
        }
        filters.eq(FlowInstance::getInitiatorId, currentUserId());
        if (request.modelName() != null) {
            filters.eq(FlowInstance::getModelName, request.modelName());
        }
        if (request.rowId() != null) {
            filters.eq(FlowInstance::getRowId, request.rowId());
        }
        FlexQuery query = new FlexQuery(filters, Orders.ofDesc(FlowInstance::getCreatedTime));
        query.select(INSTANCE_SUMMARY_FIELDS);
        Page<FlowInstance> page = Page.of(
                request.pageNumber() == null ? 1 : request.pageNumber(),
                request.pageSize() == null ? 50 : request.pageSize());
        return ApiResponse.success(instanceService.searchInstances(query, page));
    }

    @PostMapping("/instances/approve")
    @Operation(summary = "Approve pending node", description = "Approves a pending approval node and resumes execution.")
    public ApiResponse<FlowExecutionState> approve(@Valid @RequestBody FlowApproveRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.approve(request));
    }

    @PostMapping("/instances/reject")
    @Operation(summary = "Reject pending node", description = "Rejects a pending approval node and marks the flow as rejected.")
    public ApiResponse<FlowExecutionState> reject(@Valid @RequestBody FlowRejectRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.reject(request));
    }

    @PostMapping("/instances/transfer")
    @Operation(summary = "Transfer pending approval task", description = "Transfers a pending approval task from the current actor to another actor on the same node and cycle.")
    public ApiResponse<FlowExecutionState> transfer(@Valid @RequestBody FlowTransferRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.transfer(request));
    }

    @PostMapping("/instances/delegate")
    @Operation(summary = "Delegate pending approval task", description = "Delegates a pending approval task from the current actor to another actor on the same node and cycle.")
    public ApiResponse<FlowExecutionState> delegate(@Valid @RequestBody FlowDelegateRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.delegate(request));
    }

    @PostMapping("/instances/add-sign-before")
    @Operation(summary = "Add prerequisite signer before current approver", description = "Adds a prerequisite signer before the current approver on the same approval node and cycle.")
    public ApiResponse<FlowExecutionState> addSignBefore(@Valid @RequestBody FlowAddSignBeforeRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.addSignBefore(request));
    }

    @PostMapping("/instances/add-sign-after")
    @Operation(summary = "Add follow-up signer after current approver", description = "Adds a follow-up signer after the current approver on the same approval node and cycle.")
    public ApiResponse<FlowExecutionState> addSignAfter(@Valid @RequestBody FlowAddSignAfterRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.addSignAfter(request));
    }

    @PostMapping("/instances/cc")
    @Operation(summary = "CC an approval recipient", description = "Sends a CC notification to another actor on the same approval node and cycle without changing approval ownership.")
    public ApiResponse<FlowExecutionState> cc(@Valid @RequestBody FlowCcRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.cc(request));
    }

    @PostMapping("/instances/cc/read")
    @Operation(summary = "Acknowledge a CC as read", description = "Allows the CC recipient to confirm the CC task as read without affecting approval ownership or flow progression.")
    public ApiResponse<FlowExecutionState> readCc(@Valid @RequestBody FlowCcReadRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.readCc(request));
    }

    @PostMapping("/instances/return")
    @Operation(summary = "Return pending approval", description = "Returns a pending approval to its configured target. Initiator returns move the instance to Returned, while PreviousApproval returns reopen the prior approval node.")
    public ApiResponse<FlowExecutionState> returnApproval(@Valid @RequestBody FlowReturnRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.returnApproval(request));
    }

    @PostMapping("/instances/resubmit")
    @Operation(summary = "Resubmit returned approval", description = "Allows the initiator to resubmit a returned approval instance on the same published revision and recreate the pending approval.")
    public ApiResponse<FlowExecutionState> resubmit(@Valid @RequestBody FlowResubmitRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.resubmit(request));
    }

    @PostMapping("/instances/withdraw")
    @Operation(summary = "Withdraw approval flow", description = "Allows the flow initiator to withdraw an approval flow before approval completes when enabled by policy.")
    public ApiResponse<FlowExecutionState> withdraw(@Valid @RequestBody FlowWithdrawRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.withdraw(request));
    }

    @PostMapping("/instances/urge")
    @Operation(summary = "Urge pending approvers", description = "Sends an urge notification to all pending approvers of the flow instance.")
    public ApiResponse<FlowExecutionState> urge(@Valid @RequestBody FlowUrgeRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.urge(request));
    }

    @PostMapping("/instances/comment")
    @Operation(summary = "Add comment to flow instance", description = "Adds a comment to a flow instance without changing its status. Recorded in approval audit history.")
    public ApiResponse<FlowExecutionState> addComment(@Valid @RequestBody FlowCommentRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(runtimeEngine.addComment(request));
    }

    @GetMapping("/instances/{instanceId}/nodes/{nodeId}/formPermissions")
    @Operation(summary = "Get form permissions", description = "Returns field-level form permissions configured for a specific approval node.")
    public ApiResponse<Map<String, FormFieldPermission>> getFormPermissions(
            @PathVariable String instanceId, @PathVariable String nodeId) {
        return runtimeEngine.getInstance(instanceId)
                .map(state -> {
                    accessGuard.requireInstanceViewer(state, currentUserId());
                    return ApiResponse.success(formPermissionService.getFieldPermissions(instanceId, nodeId));
                })
                .orElseGet(() -> new ApiResponse<>(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                        "Flow instance not found: " + instanceId, null));
    }

    @PostMapping("/trigger")
    @Operation(summary = "Fire trigger event", description = "Fires a trigger event that may start one or more flows whose trigger contract matches.")
    public ApiResponse<List<FlowTriggerResult>> fireTrigger(@Valid @RequestBody FlowTriggerEvent event) {
        event.setActorId(currentUserId());
        return ApiResponse.success(flowAutomationService.fire(event));
    }

    @PostMapping("/onchange")
    @Operation(summary = "Evaluate field onchange flows",
            description = "Executes all FieldOnchange-purpose flows matching the source model, "
                    + "merges fieldChanges into currentData, and returns the computed variables diff.")
    public ApiResponse<Map<String, Object>> onchange(@Valid @RequestBody FlowOnchangeRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(flowAutomationService.evaluateOnchange(request));
    }

    @PostMapping("/validate")
    @Operation(summary = "Evaluate validation flows",
            description = "Transiently runs all Validation-scenario flows bound to the source model "
                    + "against the candidate row data and returns each flow's declared outputs keyed "
                    + "by flow code. No instance is persisted.")
    public ApiResponse<Map<String, Map<String, Object>>> validate(@Valid @RequestBody FlowValidationRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(flowAutomationService.evaluateValidation(request));
    }

    private static String currentUserId() {
        Long userId = ContextHolder.getContext().getUserId();
        if (userId == null) {
            throw new FlowAuthorizationException("Authentication is required");
        }
        return userId.toString();
    }
}
