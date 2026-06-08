package io.softa.starter.tenant.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionSet;

/**
 * Service category
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Service Category")
public enum ServiceCategory {
    SUPPORT("Support"),
    ;

    @JsonValue
    private final String category;
}
