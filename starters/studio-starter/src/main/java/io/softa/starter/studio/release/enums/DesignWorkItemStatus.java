package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DesignWorkItemStatus {
    IN_PROGRESS("InProgress", "In Progress"),
    DONE("Done", "Done"),
    DEFERRED("Deferred", "Deferred"),
    CLOSED("Closed", "Closed"),
    CANCELLED("Cancelled", "Cancelled"),
    ;

    @JsonValue
    private final String status;

    private final String description;
}
