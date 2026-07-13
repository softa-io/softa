package io.softa.starter.flow.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowApprovalRecordView;
import io.softa.starter.flow.dto.FlowSentCcView;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.service.FlowApprovalRecordQueryService;

/**
 * Query endpoints for persistent flow approval records.
 */
@Tag(name = "Flow Approval Record")
@RestController
@RequestMapping("/flow/approvalRecords")
public class FlowApprovalRecordController {

    private final FlowApprovalRecordQueryService flowApprovalRecordService;

    public FlowApprovalRecordController(FlowApprovalRecordQueryService flowApprovalRecordService) {
        this.flowApprovalRecordService = flowApprovalRecordService;
    }

    @GetMapping("/instance/{instanceId}")
    @Operation(summary = "Get approval history by runtime instance",
            description = "Cross-actor view restricted to the instance's participants/initiator.")
    public ApiResponse<List<FlowApprovalRecordView>> getByInstance(@PathVariable String instanceId) {
        return ApiResponse.success(flowApprovalRecordService.getByInstanceId(instanceId, currentUserId()));
    }

    @GetMapping("/history")
    @Operation(summary = "Get approval history for current user", description = "Paged, newest first.")
    public ApiResponse<Page<FlowApprovalRecordView>> getHistory(@RequestParam(required = false) String flowCode,
                                                                @RequestParam(required = false) String instanceId,
                                                                @RequestParam(required = false) String nodeId,
                                                                @RequestParam(required = false) Integer pageNumber,
                                                                @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(flowApprovalRecordService.getHistory(
                currentUserId(), flowCode, instanceId, nodeId, pageNumber, pageSize));
    }

    @GetMapping("/cc/sent")
    @Operation(summary = "Get sender-side CC history for current user", description = "Shows CC entries sent by the current user together with recipient read acknowledgement state.")
    public ApiResponse<List<FlowSentCcView>> getSentCcHistory(@RequestParam(required = false) Boolean read,
                                                                     @RequestParam(required = false) String flowCode,
                                                                     @RequestParam(required = false) String instanceId,
                                                                     @RequestParam(required = false) String nodeId) {
        return ApiResponse.success(flowApprovalRecordService.getSentCcHistory(currentUserId(), read, flowCode, instanceId, nodeId));
    }

    private static String currentUserId() {
        Long userId = ContextHolder.getContext().getUserId();
        if (userId == null) {
            throw new FlowAuthorizationException("Authentication is required");
        }
        return userId.toString();
    }
}
