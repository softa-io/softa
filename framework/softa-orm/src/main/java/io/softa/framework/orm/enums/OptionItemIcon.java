package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.orm.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@OptionSet(label = "Option Item Icon")
public enum OptionItemIcon {
    CHECK("Check"),
    X("X"),
    BAN("Ban"),
    ALERT("Alert"),
    PAUSE("Pause"),
    INFO("Info"),
    EYE("Eye"),
    LOADER("Loader"),
    CLOCK("Clock"),
    PENDING("Pending"),
    UNDO("Undo"),
    LOCK("Lock")
    ;

    @JsonValue
    private final String code;
}
