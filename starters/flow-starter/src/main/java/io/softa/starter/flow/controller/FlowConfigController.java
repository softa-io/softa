package io.softa.starter.flow.controller;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.entity.FlowConfig;
import io.softa.starter.flow.service.FlowConfigService;

/**
 * FlowConfig Model Controller
 */
@Tag(name = "FlowConfig")
@RestController
@RequestMapping("/FlowConfig")
public class FlowConfigController extends EntityController<FlowConfigService, FlowConfig, Long> {

    /**
     * Get the flow list by model name.
     *
     * @param modelName model name
     * @return flow configuration list
     */
    @GetMapping(value = "/getByModel")
    @Operation(summary = "getByModel", description = "Get flow list by model.")
    @Parameter(name = "modelName", description = "The model name of flow", schema = @Schema(type = "string"))
    public ApiResponse<List<Map<String, Object>>> getByModel(@RequestParam String modelName) {
        Assert.isTrue(ModelManager.existModel(modelName), "Model {} not found", modelName);
        return ApiResponse.success(service.getByModel(modelName));
    }

    @GetMapping(value = "/getFlowById")
    @Operation(summary = "getFlowById", description = "Get flow config by ID.")
    @Parameter(name = "id", description = "The flow ID", schema = @Schema(type = "string"))
    public ApiResponse<FlowConfig> getFlowById(@RequestParam Long id) {
        FlowConfig flowConfig = service.getFlowById(id)
                .orElseThrow(() -> new IllegalArgumentException("FlowConfig not found by ID: {0}", id));
        return ApiResponse.success(flowConfig);
    }
}