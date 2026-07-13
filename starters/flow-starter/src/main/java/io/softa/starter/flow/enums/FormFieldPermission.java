package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Field-level permission for approval node forms.
 */
@Getter
@AllArgsConstructor
public enum FormFieldPermission {
    HIDDEN("Hidden"),
    READONLY("Readonly"),
    EDITABLE("Editable"),
    REQUIRED("Required"),
    ;

    @JsonValue
    private final String type;

    /**
     * Parse a string value to a {@link FormFieldPermission}, defaulting to {@link #READONLY}.
     */
    public static FormFieldPermission fromValue(String value) {
        if (value == null) {
            return READONLY;
        }
        return switch (value.toLowerCase()) {
            case "hidden" -> HIDDEN;
            case "readonly", "read" -> READONLY;
            case "editable", "edit", "write" -> EDITABLE;
            case "required" -> REQUIRED;
            default -> READONLY;
        };
    }
}

