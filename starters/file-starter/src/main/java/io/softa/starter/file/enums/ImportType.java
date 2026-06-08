package io.softa.starter.file.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionSet;

/**
 * Import Type Enum: distinguishes between actual import and validation-only operations.
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Import Type")
public enum ImportType {
    IMPORT("Import"),
    VALIDATE("Validate");

    @JsonValue
    private final String code;
}
