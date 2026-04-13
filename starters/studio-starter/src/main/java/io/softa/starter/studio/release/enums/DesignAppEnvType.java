package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DesignAppEnvType {
    DEV("Dev", "Dev"),
    TEST("Test", "Test"),
    UAT("UAT", "UAT"),
    PROD("Prod", "Prod");

    @JsonValue
    private final String type;
    private final String name;
}
