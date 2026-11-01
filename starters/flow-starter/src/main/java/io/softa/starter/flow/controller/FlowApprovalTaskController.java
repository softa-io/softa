package io.softa.starter.flow.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowApprovalTaskSearchRequest;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.dto.FlowInboxCountView;
import io.softa.starter.flow.dto.FlowInboxView;
import io.softa.starter.flow.service.FlowApprovalTaskQueryService;
import io.softa.starter.flow.service.FlowInboxService;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * Query endpoints for persistent flow approval tasks.
 *
 * <p>The current user's own tasks carry {@link SkipPermissionCheck}: their filters pin every row to
 * the caller (the filters a search adds are ANDed on, never in their place), so row scope cannot narrow them — it
 * could only empty them, because
 * {@code FlowApprovalTask} is a model no business role is granted. A user sees their own to-dos
 * without any configuration. The waiver sits here, where the caller's id is taken from the request
 * context, and not on the service methods, which take the actor as a parameter. An instance's task
 * list is different: anyone may ask for it, and the access guard decides with the caller's own
 * permissions, so it is not waived.
 */
@Tag(name = "Flow Approval Task")
@RestController
@RequestMapping("/flow/approvalTasks")
public class FlowApprovalTaskController {

    private final FlowApprovalTaskQueryService flowApprovalTaskService;
    private final FlowInboxService flowInboxService;

    public FlowApprovalTaskController(FlowApprovalTaskQueryService flowApprovalTaskService,
                                             FlowInboxService flowInboxService) {
        this.flowApprovalTaskService = flowApprovalTaskService;
        this.flowInboxService = flowInboxService;
    }

    @GetMapping("/counts")
    @Operation(summary = "Inbox badge counts for current user",
            description = "Pending approval count and unread CC count — the Pending box of POST /search and its "
                    + "unread CC tasks, but a cheap COUNT instead of a page probe.")
    @SkipPermissionCheck
    public ApiResponse<FlowInboxCountView> counts() {
        return ApiResponse.success(flowApprovalTaskService.countInbox(currentUserId()));
    }

    @PostMapping("/search")
    @Operation(summary = "Search the current user's tasks",
            description = "One page of the caller's own tasks in a box — Pending: approval tasks waiting on them, "
                    + "newest first; Handled: approval tasks that closed, latest closed first; Cc: CC tasks, read "
                    + "and unread, oldest first. filters (nested AND / OR, searchName for the toolbar search) and "
                    + "orders may name only stored fields of FlowApprovalTask, else 400; the filters are ANDed onto "
                    + "the caller and the box, never replacing them, and orders replace the box's order. Each "
                    + "task comes with its instance's context and every account it names, as the other reads.")
    @SkipPermissionCheck
    public ApiResponse<Page<FlowApprovalTaskView>> searchTasks(@RequestBody FlowApprovalTaskSearchRequest request) {
        return ApiResponse.success(flowApprovalTaskService.searchTasks(currentUserId(), request));
    }

    @GetMapping("/inbox")
    @Operation(summary = "Get unified inbox for current user", description = "Aggregates approval pending tasks, unread CC tasks, read CC tasks, and optionally completed approval tasks for the current user.")
    @SkipPermissionCheck
    public ApiResponse<FlowInboxView> getInbox(@RequestParam(required = false) String flowId,
                                                      @RequestParam(required = false) String instanceId,
                                                      @RequestParam(required = false) String nodeId,
                                                      @RequestParam(required = false) Boolean includeCompletedApprovals,
                                                      @RequestParam(defaultValue = "1") Integer pageNumber,
                                                      @RequestParam(defaultValue = "50") Integer pageSize) {
        return ApiResponse.success(flowInboxService.getInbox(
                currentUserId(), flowId, instanceId, nodeId, includeCompletedApprovals, pageNumber, pageSize));
    }

    @GetMapping("/instance/{instanceId}")
    @Operation(summary = "Get all approval tasks by runtime instance",
            description = "Cross-actor view restricted to the instance's participants/initiator.")
    public ApiResponse<List<FlowApprovalTaskView>> getTasksByInstance(@PathVariable String instanceId) {
        return ApiResponse.success(flowApprovalTaskService.getTasksByInstanceId(instanceId, currentUserId()));
    }
}
