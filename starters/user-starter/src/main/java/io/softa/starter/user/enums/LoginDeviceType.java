package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionItem;
import io.softa.framework.orm.annotation.OptionSet;

/**
 * WebBrowser, MobileApp, DesktopAPP, MiniProgram
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Login Device Type")
public enum LoginDeviceType {
    WEB_BROWSER("WebBrowser"),
    MOBILE_APP("MobileApp"),
    @OptionItem(label = "Desktop APP")
    DESKTOP_APP("DesktopAPP"),
    MINI_PROGRAM("MiniProgram"),
    ;

    @JsonValue
    private final String type;
}
