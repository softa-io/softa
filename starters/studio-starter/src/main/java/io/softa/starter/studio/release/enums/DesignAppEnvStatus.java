package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import io.softa.framework.orm.annotation.OptionItem;
import io.softa.framework.orm.annotation.OptionSet;

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
@OptionSet(label = "Design App Env Status")
public enum DesignAppEnvStatus {
    /** No deployment in progress — ready to accept a new deployment. */
    @OptionItem(description = "Stable")
    STABLE("Stable"),
    /** A deployment is currently running against this env. */
    @OptionItem(description = "Deploying")
    DEPLOYING("Deploying"),

    @OptionItem(description = "Importing from runtime")
    IMPORTING("Importing")
    ;

    @JsonValue
    private final String status;
}
