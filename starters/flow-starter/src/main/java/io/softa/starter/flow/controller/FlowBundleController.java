package io.softa.starter.flow.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowBundleDetailView;
import io.softa.starter.flow.dto.FlowBundleSummaryView;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.bundle.FlowBundleViews;
import io.softa.starter.flow.service.FlowBundleService;
import io.softa.starter.flow.service.FlowPublishService;

/**
 * Published-revision surface: the single source for bundle lists, per-bundle
 * detail (with the canvas-complete design snapshot), and revision activation.
 */
@Tag(name = "Flow Bundles")
@RestController
@RequestMapping("/flow/bundles")
public class FlowBundleController {

    private final FlowBundleRegistry bundleRegistry;
    private final FlowBundleService bundleService;
    private final FlowPublishService publishService;

    public FlowBundleController(FlowBundleRegistry bundleRegistry,
                                FlowBundleService bundleService,
                                FlowPublishService publishService) {
        this.bundleRegistry = bundleRegistry;
        this.bundleService = bundleService;
        this.publishService = publishService;
    }

    @GetMapping
    @Operation(summary = "List bundles",
            description = "With flowId: all published revisions of that design, newest first. "
                    + "Without: the currently active bundle of every design of the caller's tenant.")
    public ApiResponse<List<FlowBundleSummaryView>> list(@RequestParam(required = false) String flowId) {
        var definitions = flowId != null
                ? bundleRegistry.listRevisionsByFlowId(flowId)
                : bundleRegistry.list();
        return ApiResponse.success(definitions.stream().map(FlowBundleViews::summarize).toList());
    }

    @GetMapping("/{bundleId}")
    @Operation(summary = "Get bundle detail",
            description = "Summary of one published revision. include=design adds the design snapshot "
                    + "taken at publish — the representation the editor uses to render a historical "
                    + "revision read-only (the compiled graph drops canvas-only fields).")
    public ApiResponse<FlowBundleDetailView> get(@PathVariable Long bundleId,
                                                 @RequestParam(required = false) String include) {
        return bundleService.findById(bundleId)
                .map(entity -> FlowBundleViews.detail(entity, "design".equalsIgnoreCase(include)))
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                        "Flow bundle not found: " + bundleId));
    }

    @PostMapping("/{bundleId}/activate")
    @Operation(summary = "Activate bundle",
            description = "Makes this published revision the design's effective one — rollback and "
                    + "roll-forward are the same operation.")
    public ApiResponse<FlowBundleSummaryView> activate(@PathVariable Long bundleId) {
        return publishService.activateBundle(bundleId)
                .map(FlowBundleViews::summarize)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(ResponseCode.REQUEST_NOT_FOUND,
                        "Flow bundle not found: " + bundleId));
    }
}
