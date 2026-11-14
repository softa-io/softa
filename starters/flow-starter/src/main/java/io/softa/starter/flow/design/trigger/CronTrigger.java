package io.softa.starter.flow.design.trigger;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Trigger fired on the flow's own schedule.
 * <p>
 * The active revision of a Cron-triggered flow is a job of cron-starter's scheduler, started in the flow's tenant
 * — a platform-shared flow once in every active tenant — at each execution of {@code cronExpression}. Trigger
 * events never start it, nor does another cron job's tick.
 */
@Schema(name = "CronTrigger")
public record CronTrigger(

        @Schema(description = "Quartz cron expression: second minute hour day-of-month month day-of-week [year], "
                + "e.g. \"0 0 9 ? * MON-FRI\" for 09:00 on weekdays")
        String cronExpression

) implements TriggerSource {}
