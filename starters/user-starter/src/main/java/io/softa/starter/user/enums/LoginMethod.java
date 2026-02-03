package io.softa.starter.user.enums;


import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Login Method
 */
@Getter
@AllArgsConstructor
public enum LoginMethod {
    APPLE_ID("Apple"),
    GOOGLE("Google"),
    TIKTOK("TikTok"),
    X("X"),

    FACEBOOK("Facebook"),
    LINKEDIN("LinkedIn"),
    MICROSOFT("Microsoft"),
    GITHUB("GitHub"),

    PASSWORD("Password"),
    SMS_CODE("SmsCode"),
    EMAIL_CODE("EmailCode"),
    SSO("SSO"),

    WE_CHAT("WeChat"),
    ALIPAY("Alipay"),
    ;

    @JsonValue
    private final String method;
}
