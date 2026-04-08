package io.softa.starter.tenant.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Service category
 */
@Getter
@AllArgsConstructor
public enum ServiceCategory {
    SUPPORT("SUPPORT"),
    ;

    @JsonValue
    private final String category;
}
