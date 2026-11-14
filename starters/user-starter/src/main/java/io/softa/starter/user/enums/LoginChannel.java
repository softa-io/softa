package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * Which identifier a person signed in with.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum LoginChannel {
    MOBILE("Mobile"),
    EMAIL("Email"),
    ;

    @JsonValue
    private final String code;

    public static LoginChannel of(String identifier) {
        return identifier != null && identifier.contains("@") ? EMAIL : MOBILE;
    }
}
