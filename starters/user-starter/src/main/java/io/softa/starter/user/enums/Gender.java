package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionSet;

/**
 * Male, Female
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Gender")
public enum Gender {
    MALE("Male"),
    FEMALE("Female");

    @JsonValue
    private final String gender;
}
