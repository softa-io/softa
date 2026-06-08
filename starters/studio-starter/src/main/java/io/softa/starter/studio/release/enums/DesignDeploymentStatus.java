package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionSet;

/**
 * Deployment lifecycle status.
 * <p>
 * {@code PENDING} → {@code DEPLOYING} → {@code SUCCESS} / {@code FAILURE} / {@code ROLLED_BACK}
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Design Release Deployment Status")
public enum DesignDeploymentStatus {
    PENDING("Pending"),
    DEPLOYING("Deploying"),
    SUCCESS("Success"),
    FAILURE("Failure"),
    CANCELED("Canceled"),
    ROLLED_BACK("Rolled Back"),
    ;

    @JsonValue
    private final String status;
}
