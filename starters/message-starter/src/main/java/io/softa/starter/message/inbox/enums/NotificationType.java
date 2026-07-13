package io.softa.starter.message.inbox.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Source category of an inbox notification.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum NotificationType {
    SYSTEM("System"),
    WORKFLOW("Workflow"),
    MANUAL("Manual");

    @JsonValue
    private final String code;
}
