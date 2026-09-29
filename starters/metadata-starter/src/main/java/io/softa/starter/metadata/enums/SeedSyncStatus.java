package io.softa.starter.metadata.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Outcome of one run of the platform seed sync, recorded on
 * {@link io.softa.starter.metadata.entity.SeedSyncBatch}.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum SeedSyncStatus {
    // The platform step, or tenants still being brought up to date.
    RUNNING("Running"),
    SUCCEEDED("Succeeded"),
    // The platform step failed: a file failed to load, or the instance running it stopped before it
    // finished. Files loaded before that point are recorded as synced; the failed one and those after it
    // stay pending, and no tenant is touched.
    FAILED("Failed"),
    // The platform step succeeded and at least one tenant failed; the other tenants are done.
    FINISHED_WITH_FAILURES("FinishedWithFailures"),
    ;

    @JsonValue
    private final String status;
}
