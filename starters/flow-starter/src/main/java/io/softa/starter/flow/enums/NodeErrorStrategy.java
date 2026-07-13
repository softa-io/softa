package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Error handling strategy for any node.
 * Configurable per-node via {@code NodeErrorConfig.strategy}.
 */
@Getter
@AllArgsConstructor
public enum NodeErrorStrategy {
    /** Propagate the error and fail the flow (default). */
    FAIL("Fail"),
    /** Retry the node up to the configured retry count (immediate, no delay) before failing. */
    RETRY("Retry"),
    ;

    @JsonValue
    private final String type;

    @JsonCreator
    public static NodeErrorStrategy fromValue(String value) {
        if (value == null) {
            return FAIL;
        }
        for (NodeErrorStrategy s : values()) {
            if (s.type.equalsIgnoreCase(value) || s.name().equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unsupported node error strategy: " + value);
    }
}
