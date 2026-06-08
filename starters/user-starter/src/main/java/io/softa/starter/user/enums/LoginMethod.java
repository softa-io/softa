package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionItem;
import io.softa.framework.orm.annotation.OptionSet;

/**
 * Login Method
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Login Method")
public enum LoginMethod {
    @OptionItem(label = "Apple")
    APPLE_ID("Apple"),
    GOOGLE("Google"),
    @OptionItem(label = "TikTok")
    TIKTOK("TikTok"),
    X("X"),
    FACEBOOK("Facebook"),
    @OptionItem(label = "LinkedIn")
    LINKEDIN("LinkedIn"),
    MICROSOFT("Microsoft"),
    @OptionItem(label = "GitHub")
    GITHUB("GitHub"),
    PASSWORD("Password"),
    @OptionItem(label = "SMS Code")
    SMS_CODE("SmsCode"),
    EMAIL_CODE("EmailCode"),
    @OptionItem(label = "SSO")
    SSO("SSO"),
    @OptionItem(label = "WeChat")
    WE_CHAT("WeChat"),
    ALIPAY("Alipay"),
    ;

    @JsonValue
    private final String method;
}
