package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Supported targets for returning a pending approval.
 */
@Getter
@AllArgsConstructor
public enum ApprovalReturnTarget {
    INITIATOR("Initiator", "Initiator"),
    PREVIOUS_APPROVAL("PreviousApproval", "Previous Approval"),
    SPECIFIC_NODE("SpecificNode", "Specific Node"),
    ;

    private final String type;
    private final String name;

    @JsonValue
    public String getType() {
        return type;
    }

    @JsonCreator
    public static ApprovalReturnTarget fromValue(String value) {
        for (ApprovalReturnTarget target : values()) {
            if (target.type.equalsIgnoreCase(value) || target.name().equalsIgnoreCase(value)) {
                return target;
            }
        }
        throw new IllegalArgumentException("Unsupported approval return target: " + value);
    }
}

