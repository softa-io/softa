package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Outgoing mail protocol.
 */
@Getter
@AllArgsConstructor
public enum SendProtocol {
    SMTP("SMTP"),
    SMTPS("SMTPS");

    @JsonValue
    private final String code;
}
