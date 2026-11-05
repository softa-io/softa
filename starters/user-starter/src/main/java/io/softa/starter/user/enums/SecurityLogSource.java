package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * Where a security-relevant change was made.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum SecurityLogSource {
    EMPLOYEE_PROFILE("EmployeeProfile"),
    USER_ACCOUNTS("UserAccounts"),
    APP("App"),
    /** Web or H5 self-service: forgot password, the join page. */
    WEB("Web"),
    SYSTEM("System"),
    ;

    @JsonValue
    private final String code;
}
