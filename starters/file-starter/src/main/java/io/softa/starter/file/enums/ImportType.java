package io.softa.starter.file.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Import Type Enum: distinguishes between actual import and validation-only operations.
 */
@Getter
@AllArgsConstructor
public enum ImportType {
    IMPORT("Import", "Import"),
    VALIDATE("Validate", "Validate");

    @JsonValue
    private final String code;
    private final String name;
}

