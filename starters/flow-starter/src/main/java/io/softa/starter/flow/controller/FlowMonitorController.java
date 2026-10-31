package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.orm.domain.Page;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowBatchActionResult;
import io.softa.starter.flow.dto.FlowDelegationView;
import io.softa.starter.flow.dto.FlowInstanceSearchRequest;
import io.softa.starter.flow.dto.FlowInstanceSummaryView;
import io.softa.starter.flow.dto.FlowReassignApproverRequest;
import io.softa.starter.flow.dto.FlowReassignPreview;
import io.softa.starter.flow.dto.FlowTerminateBatchRequest;
import io.softa.starter.flow.dto.FlowUrgeStatus;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.runtime.api.FlowUrgeRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.UrgeThrottle;
import io.softa.starter.flow.runtime.monitor.FlowHealthSnapshot;
import io.softa.starter.flow.runtime.monitor.FlowMonitorService;
import io.softa.starter.flow.service.FlowAdministrationService;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.query.FlowInstanceSummaryComposer;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.view.FlowDelegationViews;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * Operator-facing endpoints that expose flow runtime health signals, cross-initiator instance queries for
 * monitoring consoles, and what an administrator does to an instance from there.
 */
@Tag(name = "Flow Monitor")
@RestController
@RequestMapping("/flow/monitor")
public class FlowMonitorController {

    private final FlowMonitorService monitorService;

    private final FlowInstanceService instanceService;

    private final FlowRuntimeEngine runtimeEngine;

    private final FlowInstanceAccessGuard accessGuard;

    private final UrgeThrottle urgeThrottle;

    private final FlowInstanceSummaryComposer summaryComposer;

    private final FlowAdministrationService administrationService;

    private final FlowDelegationViews delegationViews;

    public FlowMonitorController(FlowMonitorService monitorService, FlowInstanceService instanceService,
                                 FlowRuntimeEngine runtimeEngine, FlowInstanceAccessGuard accessGuard,
                                 UrgeThrottle urgeThrottle, FlowInstanceSummaryComposer summaryComposer,
                                 FlowAdministrationService administrationService, FlowDelegationViews delegationViews) {
        this.monitorService = monitorService;
        this.instanceService = instanceService;
        this.runtimeEngine = runtimeEngine;
        this.accessGuard = accessGuard;
        this.urgeThrottle = urgeThrottle;
        this.summaryComposer = summaryComposer;
        this.administrationService = administrationService;
        this.delegationViews = delegationViews;
    }

    @GetMapping("/health")
    @Operation(summary = "Flow runtime health snapshot",
            description = "Returns per-status instance counts and overdue-timer count for the flow runtime.")
    public ApiResponse<FlowHealthSnapshot> health() {
        return ApiResponse.success(monitorService.snapshot());
    }

    @PostMapping("/instances/search")
    @Operation(summary = "Search flow instances (monitor)",
            description = "Cross-initiator paged instance summaries for operator monitoring views, read with the "
                    + "caller's own permissions: the FlowInstance rows their roles' data scope reaches. Honors the "
                    + "request's initiatorId filter when present. Heavy JSON state columns and the trace are "
                    + "excluded — fetch a single instance or its overlay for detail. Each waiting approval's urge "
                    + "says whether the caller may urge it now as an administrator, and if not, when.")
    public ApiResponse<Page<FlowInstanceSummaryView>> searchInstances(@RequestBody FlowInstanceSearchRequest request) {
        return ApiResponse.success(summaryComposer.summarizeForAdministrator(
                instanceService.searchSummaries(request, null), currentUserId()));
    }

    @PostMapping("/instances/urge")
    @Operation(summary = "Urge pending approvers (administrator)",
            description = "Reminds the approvers an approval waits on, as an administrator rather than its "
                    + "initiator: allowed to whoever may read the instance's FlowInstance row with their own "
                    + "permissions, whether or not the flow lets its initiator urge. The flow's interval applies: a "
                    + "step urged within it, by anyone, is not urged again. Returns when the approval may be urged "
                    + "next.")
    public ApiResponse<FlowUrgeStatus> urge(@Valid @RequestBody FlowUrgeRequest request) {
        String userId = currentUserId();
        accessGuard.requireInstanceAdministrator(request.getInstanceId(), userId);
        request.setActorId(userId);
        return ApiResponse.success(urgeThrottle.status(runtimeEngine.urgeAsAdministrator(request), LocalDateTime.now()));
    }

    @PostMapping("/instances/terminate")
    @Operation(summary = "Terminate approvals",
            description = "Ends each instance named that runs, waits or failed — its record cancelled — with one "
                    + "reason (required), recorded on each and sent to its initiator. Only instances the caller's "
                    + "own permissions read; each acted on separately. Returns each one's outcome.")
    public ApiResponse<FlowBatchActionResult> terminate(@Valid @RequestBody FlowTerminateBatchRequest request) {
        return ApiResponse.success(administrationService.terminate(request.getInstanceIds(), request.getReason(),
                currentUserId()));
    }

    @PostMapping("/approvers/reassign/preview")
    @Operation(summary = "Preview a reassignment",
            description = "How many of the original approver's pending approval tasks — on the instances the "
                    + "caller's permissions read — a reassignment would move.")
    public ApiResponse<FlowReassignPreview> previewReassign(@Valid @RequestBody FlowReassignApproverRequest request) {
        return ApiResponse.success(administrationService.previewReassign(request.getFromActorId(), request.getToActorId()));
    }

    @PostMapping("/approvers/reassign")
    @Operation(summary = "Reassign an approver's pending tasks",
            description = "Moves each of the original approver's pending approval tasks — on the instances the "
                    + "caller's permissions read — to the new approver, with a reason (required), each recorded "
                    + "as the approver's transfer with the caller as its operator. Steps not reached yet are not "
                    + "touched: they resolve their approvers when reached. Returns each task's outcome.")
    public ApiResponse<FlowBatchActionResult> reassign(@Valid @RequestBody FlowReassignApproverRequest request) {
        return ApiResponse.success(administrationService.reassign(request.getFromActorId(), request.getToActorId(),
                request.getReason(), currentUserId()));
    }

    @PostMapping("/delegations")
    @Operation(summary = "Delegate on an approver's behalf",
            description = "Creates a delegation rule for the approver named as delegator: their tasks arriving "
                    + "from then on go to the delegate. It affects all of their tasks, whatever the caller's data "
                    + "scope — which is why it is a permission of its own. The approver sees it among theirs.")
    public ApiResponse<FlowDelegation> delegateOnBehalf(@RequestBody FlowDelegation delegation) {
        return ApiResponse.success(administrationService.delegateOnBehalf(delegation));
    }

    @GetMapping("/delegations")
    @Operation(summary = "List delegation rules", description = "Every delegation rule of the tenant, newest first, its delegator and delegate named.")
    public ApiResponse<List<FlowDelegationView>> listDelegations() {
        return ApiResponse.success(delegationViews.of(administrationService.listDelegations()));
    }

    @PostMapping("/delegations/{id}/cancel")
    @Operation(summary = "Cancel a delegation rule", description = "Deactivates any delegation rule of the tenant.")
    public ApiResponse<Boolean> cancelDelegation(@PathVariable Long id) {
        administrationService.cancelDelegation(id);
        return ApiResponse.success(true);
    }
}
