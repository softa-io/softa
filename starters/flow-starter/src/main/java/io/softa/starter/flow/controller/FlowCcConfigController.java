package io.softa.starter.flow.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.entity.FlowCcConfig;
import io.softa.starter.flow.service.FlowCcConfigService;

/**
 * REST endpoints for managing CC (carbon copy) configurations.
 */
@Tag(name = "Flow CC Config")
@RestController
@RequestMapping("/flow/ccConfigs")
public class FlowCcConfigController {

    private final FlowCcConfigService ccConfigService;

    public FlowCcConfigController(FlowCcConfigService ccConfigService) {
        this.ccConfigService = ccConfigService;
    }

    @PostMapping
    @Operation(summary = "Create CC configuration", description = "Creates a new CC configuration rule.")
    public ApiResponse<FlowCcConfig> create(@RequestBody FlowCcConfig config) {
        return ApiResponse.success(ccConfigService.createConfig(config));
    }

    @GetMapping
    @Operation(summary = "List CC configurations", description = "Lists all CC configurations for a flow code.")
    public ApiResponse<List<FlowCcConfig>> list(@RequestParam String flowCode) {
        return ApiResponse.success(ccConfigService.listByFlowCode(flowCode));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate CC configuration", description = "Deactivates a CC configuration rule.")
    public ApiResponse<Void> deactivate(@PathVariable Long id) {
        ccConfigService.deactivateConfig(id);
        return ApiResponse.success(null);
    }
}

