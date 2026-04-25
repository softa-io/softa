package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Runtime status of a {@code DesignAppEnv}. Used as a per-environment mutex
 * to serialize deployments: only a {@code STABLE} env may transition to
 * {@code DEPLOYING}, and the env is released back to {@code STABLE} when the
 * deployment finishes (success or failure).
 * <p>
 * The transition is performed via a conditional update (compare-and-set on
 * {@code envStatus}) so two concurrent callers cannot both acquire the lock.
 */
@Getter
@AllArgsConstructor
public enum DesignAppEnvStatus {
    /** No deployment in progress — ready to accept a new deployment. */
    STABLE("Stable", "Stable"),
    /** A deployment is currently running against this env. */
    DEPLOYING("Deploying", "Deploying"),

    IMPORTING("Importing", "Importing from runtime")
    ;

    @JsonValue
    private final String status;

    private final String description;
}
