package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Dynamic approver source types supported by approval nodes.
 */
@Getter
@AllArgsConstructor
public enum ApproverSourceType {
    VARIABLE_LIST("VariableList"),
    EXPRESSION("Expression"),
    INITIATOR_MANAGER("InitiatorManager"),
    ROLE("Role"),
    SUPERVISOR("Supervisor"),
    DEPT_LEADER("DeptLeader"),
    ROLE_QUERY("RoleQuery"),
    POSITION("Position"),
    DEPARTMENT("Department"),
    ;

    @JsonValue
    private final String type;

    @JsonCreator
    public static ApproverSourceType fromValue(String value) {
        for (ApproverSourceType type : values()) {
            if (type.type.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported approver source type: " + value);
    }
}

