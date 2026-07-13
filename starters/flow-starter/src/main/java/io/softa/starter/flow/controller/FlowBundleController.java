package io.softa.starter.flow.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowBundleDetailView;
import io.softa.starter.flow.dto.FlowBundleSummaryView;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.runtime.bundle.FlowBundleMapper;
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
            description = "With designId: all published revisions of that design, newest first. "
                    + "Without: the currently active bundle of every design.")
    public ApiResponse<List<FlowBundleSummaryView>> list(@RequestParam(required = false) Long designId) {
        var definitions = designId != null
                ? bundleRegistry.listRevisionsByDesignId(designId)
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
                .map(entity -> toDetail(entity, "design".equalsIgnoreCase(include)))
                .map(ApiResponse::success)
                .orElseGet(() -> new ApiResponse<>(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                        "Flow bundle not found: " + bundleId, null));
    }

    @PostMapping("/{bundleId}/activate")
    @Operation(summary = "Activate bundle",
            description = "Makes this published revision the design's effective one — rollback and "
                    + "roll-forward are the same operation.")
    public ApiResponse<FlowBundleSummaryView> activate(@PathVariable Long bundleId) {
        return publishService.activateBundle(bundleId)
                .map(FlowBundleViews::summarize)
                .map(ApiResponse::success)
                .orElseGet(() -> new ApiResponse<>(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                        "Flow bundle not found: " + bundleId, null));
    }

    private static FlowBundleDetailView toDetail(FlowBundle entity, boolean includeDesign) {
        FlowBundleSummaryView summary = FlowBundleViews.summarize(FlowBundleMapper.toDefinition(entity));
        return new FlowBundleDetailView(summary, includeDesign ? entity.getDesignJson() : null);
    }
}
