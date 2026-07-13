package io.softa.starter.flow.runtime.state;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Position of an add-sign action relative to the source approver.
 */
@Getter
@AllArgsConstructor
public enum AddSignPosition {
    BEFORE("Before"),
    AFTER("After"),
    ;

    @JsonValue
    private final String type;
}
