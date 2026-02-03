package io.softa.starter.user.enums;


import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * OAuth Provider
 */
@Getter
@AllArgsConstructor
public enum OAuthProvider {
    APPLE("Apple"),
    GOOGLE("Google"),
    TIKTOK("TikTok"),
    X("X"),
    LINKED_IN("LinkedIn");

    @JsonValue
    private final String provider;
}
