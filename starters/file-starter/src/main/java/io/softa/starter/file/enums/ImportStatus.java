package io.softa.starter.file.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionSet;

/**
 * Import Status Enum
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Import Status")
public enum ImportStatus {
    PROCESSING("Processing"),
    SUCCESS("Success"),
    FAILURE("Failure"),
    PARTIAL_FAILURE("PartialFailure"),
    VALIDATION_SUCCESS("ValidationSuccess"),
    VALIDATION_FAILURE("ValidationFailure"),
    ;

    @JsonValue
    private final String code;
}
