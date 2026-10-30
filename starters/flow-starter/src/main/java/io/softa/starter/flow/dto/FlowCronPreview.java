package io.softa.starter.flow.dto;

import java.time.OffsetDateTime;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A cron expression as the designer shows it: whether publishing accepts it, what it means, and when it fires next.
 */
@Schema(name = "FlowCronPreview")
public record FlowCronPreview(
        @Schema(description = "Whether publishing accepts the expression: the scheduler can run it and it fires again")
        boolean valid,
        @Schema(description = "Why publishing would refuse it, when it would")
        String message,
        @Schema(description = "What the expression means, in words")
        String description,
        @Schema(description = "The server's time zone, the one the schedule runs in")
        String zone,
        @Schema(description = "The next times it fires, in that zone")
        List<OffsetDateTime> nextRuns) {
}
