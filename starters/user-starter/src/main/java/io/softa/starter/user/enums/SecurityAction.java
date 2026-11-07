package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * What a security-log row records.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum SecurityAction {
    ENTERPRISE_EMAIL_CHANGED("EnterpriseEmailChanged"),
    RESET_USER("ResetUser"),
    UNBIND_AND_REINVITE("UnbindAndReinvite"),
    FROZEN("Frozen"),
    UNFROZEN("Unfrozen"),
    /** Any other account status change: deactivated on the leaving date, reopened for re-hire. */
    STATUS_CHANGED("StatusChanged"),
    LOGIN_MOBILE_CHANGED("LoginMobileChanged"),
    PASSWORD_CHANGED("PasswordChanged"),
    PASSWORD_RESET("PasswordReset"),
    JOIN_BLOCKED("JoinBlocked"),
    /** A dormant person's login mobile was taken back so someone else could use the number. */
    DORMANT_MOBILE_RELEASED("DormantMobileReleased"),
    /** A company-issued work mobile was taken off the person's login when the account closed. */
    WORK_MOBILE_RELEASED("WorkMobileReleased"),
    ;

    @JsonValue
    private final String code;
}
