package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Mail server authentication type.
 */
@Getter
@AllArgsConstructor
public enum AuthType {
    PASSWORD("Password", "Username and password authentication"),
    OAUTH2("OAuth2", "OAuth2 / token-based authentication");

    @JsonValue
    private final String code;
    private final String description;
}
