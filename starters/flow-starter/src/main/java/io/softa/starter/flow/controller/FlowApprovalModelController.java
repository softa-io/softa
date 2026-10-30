package io.softa.starter.flow.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.ApprovalModelView;
import io.softa.starter.flow.service.FlowDesignService;

/**
 * The documents an approval flow can be made for: a new approval flow picks one of those that have none.
 */
@Tag(name = "Flow Approval Models")
@RestController
@RequestMapping("/flow/approvalModels")
public class FlowApprovalModelController {

    private final FlowDesignService designService;

    public FlowApprovalModelController(FlowDesignService designService) {
        this.designService = designService;
    }

    @GetMapping
    @Operation(summary = "List approvable documents",
            description = "The models the application can approve — those with an approval handler — by label, each "
                    + "with its approval flow (id, name, enabled) when it has one: a document has one approval flow.")
    public ApiResponse<List<ApprovalModelView>> list() {
        return ApiResponse.success(designService.listApprovalModels());
    }
}
