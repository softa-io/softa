package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Deployment lifecycle status.
 * <p>
 * {@code PENDING} → {@code DEPLOYING} → {@code SUCCESS} / {@code FAILURE} / {@code ROLLED_BACK}
 */
@Getter
@AllArgsConstructor
public enum DesignDeploymentStatus {
    PENDING("Pending", "Pending"),
    DEPLOYING("Deploying", "Deploying"),
    SUCCESS("Success", "Success"),
    FAILURE("Failure", "Failure"),
    ROLLED_BACK("RolledBack", "Rolled Back"),
    ;

    @JsonValue
    private final String status;

    private final String description;
}

