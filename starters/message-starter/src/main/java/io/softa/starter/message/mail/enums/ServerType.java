package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Identifies whether an OAuth2 credential belongs to a sending or receiving server config.
 */
@Getter
@AllArgsConstructor
public enum ServerType {
    SEND("Send"),
    RECEIVE("Receive");

    @JsonValue
    private final String code;
}
