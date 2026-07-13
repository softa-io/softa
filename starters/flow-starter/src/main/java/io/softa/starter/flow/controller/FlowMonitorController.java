package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.runtime.monitor.FlowHealthSnapshot;
import io.softa.starter.flow.runtime.monitor.FlowMonitorService;

/**
 * Operator-facing endpoints that expose flow runtime health signals.
 */
@Tag(name = "Flow Monitor")
@RestController
@RequestMapping("/flow/monitor")
public class FlowMonitorController {

    private final FlowMonitorService monitorService;

    public FlowMonitorController(FlowMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @GetMapping("/health")
    @Operation(summary = "Flow runtime health snapshot",
            description = "Returns per-status instance counts and overdue-timer count for the flow runtime.")
    public ApiResponse<FlowHealthSnapshot> health() {
        return ApiResponse.success(monitorService.snapshot());
    }
}
