package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * OAuth2 provider for mail authentication.
 */
@Getter
@AllArgsConstructor
public enum OAuthProvider {
    GOOGLE("Google"),
    MICROSOFT("Microsoft"),
    CUSTOM("Custom");

    @JsonValue
    private final String code;
}
