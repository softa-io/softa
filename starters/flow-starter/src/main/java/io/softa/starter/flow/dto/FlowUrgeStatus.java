package io.softa.starter.flow.dto;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether an approval may be urged now, and if not, when it may be. Each step the approval waits on is
 * urged at most once per its flow's interval ({@code urgeIntervalHours}, default 4), whether its
 * initiator urges it or an administrator does; the initiator of a flow that turns urging off
 * ({@code urgeEnabled}) may not urge it at all.
 */
@Schema(name = "FlowUrgeStatus")
public record FlowUrgeStatus(

        @Schema(description = "Whether the approval may be urged now: it waits on a step not urged within the "
                + "flow's interval, and the caller may urge it")
        boolean urgeable,

        @Schema(description = "When it may be urged again, while every step it waits on was urged within the "
                + "interval; null otherwise, and for an initiator the flow does not let urge")
        LocalDateTime nextUrgeAt
) {
}
