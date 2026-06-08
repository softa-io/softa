package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.orm.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Outcome of the last drift check for a {@code DesignAppEnv}. Persisted on
 * {@code DesignAppEnvDrift.check_status} so the UI can tell a "no drift, last checked 2
 * minutes ago" state apart from a "check failed, runtime unreachable" state.
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Design Drift Check Status")
public enum DesignDriftCheckStatus {
    /** The drift check completed — the {@code driftContent} reflects the actual runtime state. */
    SUCCESS("Success"),
    /** The drift check failed (e.g. remote env unreachable). The previous drift content is kept as-is. */
    FAILURE("Failure"),
    ;

    @JsonValue
    private final String status;
}
