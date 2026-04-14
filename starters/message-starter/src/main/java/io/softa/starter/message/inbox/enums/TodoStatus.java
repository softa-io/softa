package io.softa.starter.message.inbox.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Lifecycle status of an inbox todo item.
 */
@Getter
@AllArgsConstructor
public enum TodoStatus {
    PENDING("Pending"),
    DONE("Done"),
    REJECTED("Rejected"),
    CANCELLED("Cancelled"),
    EXPIRED("Expired");

    @JsonValue
    private final String code;
}
