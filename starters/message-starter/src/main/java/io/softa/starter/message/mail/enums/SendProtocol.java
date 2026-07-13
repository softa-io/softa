package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Outgoing mail protocol.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum SendProtocol {
    @OptionItem(label = "SMTP")
    SMTP("SMTP"),
    @OptionItem(label = "SMTPS")
    SMTPS("SMTPS");

    @JsonValue
    private final String code;
}
