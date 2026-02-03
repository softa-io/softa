package io.softa.starter.user.enums;


import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * WebBrowser, MobileApp, DesktopAPP, MiniProgram
 */
@Getter
@AllArgsConstructor
public enum LoginDeviceType {
    WEB_BROWSER("WebBrowser"),
    MOBILE_APP("MobileApp"),
    DESKTOP_APP("DesktopAPP"),
    MINI_PROGRAM("MiniProgram"),
    ;

    @JsonValue
    private final String type;
}
