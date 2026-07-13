package io.softa.starter.flow.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.dto.FlowInboxView;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.service.FlowApprovalTaskQueryService;
import io.softa.starter.flow.service.FlowInboxService;

/**
 * Query endpoints for persistent flow approval tasks.
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

    @GetMapping("/pending")
    @Operation(summary = "Page pending tasks for current user")
    public ApiResponse<Page<FlowApprovalTaskView>> pagePendingTasks(@RequestParam(required = false) String flowCode,
                                                                    @RequestParam(required = false) String instanceId,
                                                                    @RequestParam(required = false) String nodeId,
                                                                    @RequestParam(defaultValue = "1") Integer pageNumber,
                                                                    @RequestParam(defaultValue = "50") Integer pageSize) {
        return ApiResponse.success(flowApprovalTaskService
                .pagePendingTasks(currentUserId(), flowCode, instanceId, nodeId, pageNumber, pageSize));
    }

    @GetMapping("/completed")
    @Operation(summary = "Page completed tasks for current user")
    public ApiResponse<Page<FlowApprovalTaskView>> pageCompletedTasks(@RequestParam(required = false) String flowCode,
                                                                      @RequestParam(required = false) String instanceId,
                                                                      @RequestParam(required = false) String nodeId,
                                                                      @RequestParam(defaultValue = "1") Integer pageNumber,
                                                                      @RequestParam(defaultValue = "50") Integer pageSize) {
        return ApiResponse.success(flowApprovalTaskService
                .pageCompletedTasks(currentUserId(), flowCode, instanceId, nodeId, pageNumber, pageSize));
    }

    @GetMapping("/cc")
    @Operation(summary = "Page CC tasks for current user", description = "Queries unread or read CC tasks for the current user. read=false returns unread CC tasks, read=true returns acknowledged CC tasks, and omitting read returns both.")
    public ApiResponse<Page<FlowApprovalTaskView>> pageCcTasks(@RequestParam(required = false) Boolean read,
                                                               @RequestParam(required = false) String flowCode,
                                                               @RequestParam(required = false) String instanceId,
                                                               @RequestParam(required = false) String nodeId,
                                                               @RequestParam(defaultValue = "1") Integer pageNumber,
                                                               @RequestParam(defaultValue = "50") Integer pageSize) {
        return ApiResponse.success(flowApprovalTaskService
                .pageCcTasks(currentUserId(), read, flowCode, instanceId, nodeId, pageNumber, pageSize));
    }

    @GetMapping("/inbox")
    @Operation(summary = "Get unified inbox for current user", description = "Aggregates approval pending tasks, unread CC tasks, read CC tasks, and optionally completed approval tasks for the current user.")
    public ApiResponse<FlowInboxView> getInbox(@RequestParam(required = false) String flowCode,
                                                      @RequestParam(required = false) String instanceId,
                                                      @RequestParam(required = false) String nodeId,
                                                      @RequestParam(required = false) Boolean includeCompletedApprovals,
                                                      @RequestParam(defaultValue = "1") Integer pageNumber,
                                                      @RequestParam(defaultValue = "50") Integer pageSize) {
        return ApiResponse.success(flowInboxService.getInbox(
                currentUserId(), flowCode, instanceId, nodeId, includeCompletedApprovals, pageNumber, pageSize));
    }

    @GetMapping("/instance/{instanceId}")
    @Operation(summary = "Get all approval tasks by runtime instance",
            description = "Cross-actor view restricted to the instance's participants/initiator.")
    public ApiResponse<List<FlowApprovalTaskView>> getTasksByInstance(@PathVariable String instanceId) {
        return ApiResponse.success(flowApprovalTaskService.getTasksByInstanceId(instanceId, currentUserId()));
    }

    private static String currentUserId() {
        Long userId = ContextHolder.getContext().getUserId();
        if (userId == null) {
            throw new FlowAuthorizationException("Authentication is required");
        }
        return userId.toString();
    }
}
