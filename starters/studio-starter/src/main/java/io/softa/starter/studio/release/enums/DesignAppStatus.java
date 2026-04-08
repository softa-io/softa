package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DesignAppStatus {
    ACTIVE("Active", "Active"),
    MAINTENANCE("Maintenance", "Maintenance"),
    DEPRECATED("Deprecated", "Deprecated"),
    ;

    @JsonValue
    private final String status;

    private final String description;
}
