package io.softa.starter.flow.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Trigger event log row for monitoring lists. Excludes server-side columns
 * (tenantId) and the potentially large trigger-parameters JSON — fetch a
 * single event via the generic model API when the payload is needed.
 */
@Schema(name = "FlowEventView")
public record FlowEventView(

        @Schema(description = "Event id")
        Long id,

        @Schema(description = "Trigger type discriminator (e.g. EntityChange, Api, Cron)")
        String triggerType,

        @Schema(description = "Source model when the trigger is entity-related")
        String sourceModel,

        @Schema(description = "Source row id when the trigger is entity-related")
        String sourceRowId,

        @Schema(description = "Actor who triggered the event")
        String actorId,

        @Schema(description = "Flow code of the matched and started flow")
        String flowCode,

        @Schema(description = "Flow revision that was started")
        Integer flowRevision,

        @Schema(description = "Runtime instance id of the started flow")
        String instanceId,

        @Schema(description = "Whether the flow was started successfully")
        Boolean success,

        @Schema(description = "Error message when the flow failed to start")
        String errorMessage,

        @Schema(description = "Trigger fire method")
        String fireMethod,

        @Schema(description = "Event timestamp")
        LocalDateTime eventTime
) {
}
