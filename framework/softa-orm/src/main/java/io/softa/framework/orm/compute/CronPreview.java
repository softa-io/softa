package io.softa.framework.orm.compute;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A cron expression as an editor shows it beside the field: whether it is accepted, what it means in words, and when
 * it fires next, in the server's zone — the one the scheduler runs it in.
 */
@Schema(name = "CronPreview")
public record CronPreview(
        @Schema(description = "Whether the expression is accepted: the scheduler can run it and it fires again")
        boolean valid,
        @Schema(description = "Why it is not accepted, when it is not")
        String message,
        @Schema(description = "What the expression means, in words")
        String description,
        @Schema(description = "The server's time zone, the one the schedule runs in")
        String zone,
        @Schema(description = "The next times it fires, in that zone")
        List<OffsetDateTime> nextRuns) {

    /** An expression that is not accepted, and why. */
    public static CronPreview refused(String message) {
        return new CronPreview(false, message, null, ZoneId.systemDefault().getId(), List.of());
    }
}
