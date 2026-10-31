package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowCronPreview;
import io.softa.starter.flow.runtime.engine.FlowCron;

/**
 * Previews a cron expression for the designer — a Cron trigger's schedule or a Timer node's — the way publishing
 * and the scheduler read it.
 */
@Tag(name = "Flow Cron")
@RestController
@RequestMapping("/flow/cron")
public class FlowCronController {

    @GetMapping("/preview")
    @Operation(summary = "Preview a cron expression",
            description = "Whether publishing accepts it (Quartz: second minute hour day-of-month month day-of-week "
                    + "[year], one day field `?`), what it means in words, and its next runs in the server's zone. "
                    + "`count` is clamped to 1–" + FlowCron.MAX_PREVIEW_RUNS + ".")
    public ApiResponse<FlowCronPreview> preview(@RequestParam String expression,
                                                @RequestParam(defaultValue = "5") int count) {
        return ApiResponse.success(FlowCron.preview(expression, count));
    }
}
