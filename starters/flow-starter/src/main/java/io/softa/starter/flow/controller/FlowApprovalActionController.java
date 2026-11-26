package io.softa.starter.flow.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowApprovalActionView;
import io.softa.starter.flow.service.FlowApprovalActionQueryService;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * Query endpoints for persistent flow approval records: an instance's history, ruled on by the access guard —
 * {@code FlowApprovalAction} is granted to no business role.
 */
@Tag(name = "Flow Approval Record")
@RestController
@RequestMapping("/flow/approvalActions")
public class FlowApprovalActionController {

    private final FlowApprovalActionQueryService flowApprovalActionService;

    public FlowApprovalActionController(FlowApprovalActionQueryService flowApprovalActionService) {
        this.flowApprovalActionService = flowApprovalActionService;
    }

    @GetMapping("/instance/{instanceId}")
    @Operation(summary = "Get approval history by runtime instance",
            description = "Cross-actor view restricted to the instance's participants/initiator.")
    public ApiResponse<List<FlowApprovalActionView>> getByInstance(@PathVariable String instanceId) {
        return ApiResponse.success(flowApprovalActionService.getByInstanceId(instanceId, currentUserId()));
    }
}
