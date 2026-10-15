package io.softa.starter.metadata.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Where bringing one tenant up to date in a batch stands.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum SeedSyncTaskStatus {
    PENDING("Pending"),
    RUNNING("Running"),
    SUCCEEDED("Succeeded"),
    // Everything the task wrote in the tenant was rolled back; the reason is on the task.
    FAILED("Failed"),
    // The tenant was no longer in a state to be synced when its turn came.
    SKIPPED("Skipped"),
    ;

    @JsonValue
    private final String status;
}
