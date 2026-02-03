package io.softa.starter.user.enums;


import com.fasterxml.jackson.annotation.JsonValue;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Male, Female, Other
 */
@Getter
@AllArgsConstructor
public enum Gender {
    MALE("Male"),
    FEMALE("Female");

    @JsonValue
    private final String gender;
}
