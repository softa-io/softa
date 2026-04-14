package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Incoming mail protocol.
 */
@Getter
@AllArgsConstructor
public enum ReceiveProtocol {
    IMAP("IMAP"),
    IMAPS("IMAPS"),
    POP3("POP3"),
    POP3S("POP3S");

    @JsonValue
    private final String code;
}
