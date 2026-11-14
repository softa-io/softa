package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * What a login-history row records.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum LoginEvent {
    LOGIN_SUCCESS("LoginSuccess"),
    LOGIN_FAILED("LoginFailed"),
    LOCKED("Locked"),
    UNLOCKED("Unlocked"),
    PASSWORD_SET("PasswordSet"),
    /** A request found the account Frozen or Deactivated and ended its session. */
    ORG_UNAVAILABLE("OrgUnavailable"),
    /** Reset User or unbind-and-reinvite ended the session. */
    RESET_USER("ResetUser"),
    TENANT_SWITCHED("TenantSwitched"),
    LOGOUT("Logout"),
    /** Ended because the password or login mobile was changed on another device, or reset. */
    KICKED("Kicked"),
    ;

    @JsonValue
    private final String code;
}
