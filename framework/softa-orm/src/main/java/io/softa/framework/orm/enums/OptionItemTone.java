package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OptionItemTone {
    SUCCESS("Success", "Success"),
    WARNING("Warning", "Warning"),
    ERROR("Error", "Error"),
    INFO("Info", "Info"),
    NEUTRAL("Neutral", "Neutral")
    ;

    @JsonValue
    private final String code;
    private final String name;
}
