package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OptionItemIcon {
    CHECK("Check", "Check"),
    X("X", "X"),
    BAN("Ban", "Ban"),
    ALERT("Alert", "Alert"),
    PAUSE("Pause", "Pause"),
    INFO("Info", "Info"),
    EYE("Eye", "Eye"),
    LOADER("Loader", "Loader"),
    CLOCK("Clock", "Clock"),
    PENDING("Pending", "Pending"),
    UNDO("Undo", "Undo"),
    LOCK("Lock", "Lock")
    ;

    @JsonValue
    private final String code;
    private final String name;
}
