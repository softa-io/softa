package io.softa.starter.flow.runtime.monitor;

import java.time.Instant;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * Aggregate health snapshot of the flow runtime, exposed via {@code GET /flow/monitor/health}.
 */
@Data
@Builder
@Schema(name = "FlowHealthSnapshot")
public class FlowHealthSnapshot {

    @Schema(description = "When the snapshot was taken")
    private Instant capturedAt;

    @Schema(description = "Instance count by execution status (enum name)")
    private Map<String, Long> instanceCountByStatus;

    @Schema(description = "WAITING instances with a due timer whose next_fire_at has already passed")
    private long overdueTimerCount;
}
