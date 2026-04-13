package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DesignPortfolioStatus {
    ACTIVE("Active", "Active"),
    ARCHIVED("Archived", "Archived"),
    ;

    @JsonValue
    private final String status;

    private final String description;
}
