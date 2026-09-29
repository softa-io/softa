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
    RUNNING("Running"),
    SUCCEEDED("Succeeded"),
    // A file failed to load, or the instance running the batch stopped before it finished. Files loaded
    // before that point are recorded as synced; the failed one and those after it stay pending.
    FAILED("Failed"),
    ;

    @JsonValue
    private final String status;
}
