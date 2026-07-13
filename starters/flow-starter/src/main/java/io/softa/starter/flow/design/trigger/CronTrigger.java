package io.softa.starter.flow.design.trigger;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Trigger fired on a cron schedule via the Pulsar CronTask consumer.
 */
@Schema(name = "CronTrigger")
public record CronTrigger(

        @Schema(description = "Cron expression (e.g. \"0 0 * * *\" for daily midnight)")
        String cronExpression

) implements TriggerSource {}
