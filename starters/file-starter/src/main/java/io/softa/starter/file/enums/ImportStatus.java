package io.softa.starter.file.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Import Status Enum
 */
@Getter
@AllArgsConstructor
public enum ImportStatus {
    PROCESSING("Processing", "Processing"),
    SUCCESS("Success", "Success"),
    FAILURE("Failure", "Failure"),
    PARTIAL_FAILURE("PartialFailure", "Partial Failure"),
    VALIDATION_SUCCESS("ValidationSuccess", "Validation Success"),
    VALIDATION_FAILURE("ValidationFailure", "Validation Failure"),
    ;

    @JsonValue
    private final String code;
    private final String name;
}
