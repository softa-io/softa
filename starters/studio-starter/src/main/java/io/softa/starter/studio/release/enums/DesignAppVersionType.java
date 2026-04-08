package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DesignAppVersionType {
    NORMAL("Normal", "Normal planned release"),
    HOTFIX("Hotfix", "Emergency hotfix release"),
    ;

    @JsonValue
    private final String type;

    private final String description;
}
