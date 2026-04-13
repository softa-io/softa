package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DesignAppVersionStatus {
    DRAFT("Draft", "Draft"),
    SEALED("Sealed", "Sealed"),
    FROZEN("Frozen", "Frozen"),
    ;

    @JsonValue
    private final String status;

    private final String description;
}
