package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.orm.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@OptionSet(label = "Option Item Tone")
public enum OptionItemTone {
    SUCCESS("Success"),
    WARNING("Warning"),
    ERROR("Error"),
    INFO("Info"),
    NEUTRAL("Neutral")
    ;

    @JsonValue
    private final String code;
}
