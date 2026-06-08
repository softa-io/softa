package io.softa.starter.cron.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionItem;
import io.softa.framework.orm.annotation.OptionSet;

/**
 * Cron status
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Cron Status")
public enum CronStatus {
    @OptionItem(description = "Scheduled for a specified time.")
    SCHEDULED("Scheduled"),
    @OptionItem(description = "Currently executing.")
    RUNNING("Running"),
    @OptionItem(description = "Finished successfully.")
    COMPLETED("Completed"),
    @OptionItem(description = "Temporarily paused.")
    PAUSED("Paused"),
    @OptionItem(description = "Cancelled before completion.")
    CANCELLED("Cancelled"),
    @OptionItem(description = "Skipped due to unmet execution conditions.")
    SKIPPED("Skipped"),
    @OptionItem(description = "Interrupted by execution timeout.")
    TIMEOUT("Timeout"),
    @OptionItem(description = "Execution failed.")
    FAILED("Failed");

    @JsonValue
    private final String status;
}
