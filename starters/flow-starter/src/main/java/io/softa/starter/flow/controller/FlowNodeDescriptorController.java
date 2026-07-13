package io.softa.starter.flow.controller;

import java.util.Collection;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.i18n.I18n;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.design.FlowNodeDescriptor;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.descriptor.FlowNodeDescriptorRegistry;

/**
 * Exposes available node types for the flow editor palette. The palette varies
 * with host configuration (conditional task executors), so the frontend must
 * always fetch it rather than shipping a static list.
 */
@Tag(name = "Flow Node Descriptors")
@RestController
@RequestMapping("/flow/nodeDescriptors")
public class FlowNodeDescriptorController {

    private final FlowNodeDescriptorRegistry registry;

    public FlowNodeDescriptorController(FlowNodeDescriptorRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    @Operation(summary = "List available node types",
            description = "Node descriptors for the editor palette, optionally filtered by scenario. "
                    + "Preview/stub node types are hidden unless includePreview=true.")
    public ApiResponse<List<FlowNodeDescriptor>> list(
            @RequestParam(required = false) FlowScenario scenario,
            @RequestParam(defaultValue = "false") boolean includePreview) {
        Collection<FlowNodeDescriptor> descriptors = scenario != null
                ? registry.listByScenario(scenario)
                : registry.list();
        List<FlowNodeDescriptor> result = descriptors.stream()
                .filter(d -> includePreview || d.productionReady())
                // labels/descriptions are English source text; translate per request language
                .map(d -> d.withText(I18n.get(d.label()), I18n.get(d.description())))
                .toList();
        return ApiResponse.success(result);
    }
}
