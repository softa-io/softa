package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Shared threshold mode for both approval and reject completion logic.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum VoteThresholdMode {
    ANY_ONE("AnyOne"),
    UNANIMOUS("Unanimous"),
    MIN_COUNT("MinCount"),
    PERCENTAGE("Percentage"),
    ;

    @JsonValue
    private final String type;

    @JsonCreator
    public static VoteThresholdMode fromValue(String value) {
        for (VoteThresholdMode mode : values()) {
            if (mode.type.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported vote threshold mode: " + value);
    }
}
