package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;


/**
 * User Layout Density
 */
@Getter
@AllArgsConstructor
public enum UserLayoutDensity {
    DEFAULT("Default"),
    COMPACT("Compact"),
    COMFORTABLE("Comfortable");

    @JsonValue
    private final String value;

}
