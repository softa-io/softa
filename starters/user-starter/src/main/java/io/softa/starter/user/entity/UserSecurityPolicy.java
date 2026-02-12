package io.softa.starter.user.entity;

import java.io.Serial;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.user.enums.LoginMethod;

/**
 * UserSecurityPolicy Model
 */
@Data
@Schema(name = "UserSecurityPolicy")
@EqualsAndHashCode(callSuper = true)
public class UserSecurityPolicy extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "Policy Name")
    private String name;

    @Schema(description = "Policy Code")
    private String code;

    @Schema(description = "Login Methods")
    private List<LoginMethod> loginMethods;

    @Schema(description = "Active Device Limit")
    private Integer activeDeviceLimit;

    @Schema(description = "Server Session Duration")
    private Integer sessionDuration;

    @Schema(description = "Client Cookie-Session Idle Duration")
    private Integer sessionIdleDuration;

    @Schema(description = "Force Change Initial Password")
    private Boolean forceChangeInitialPassword;

    @Schema(description = "Password Valid Days")
    private Integer passwordValidDays;

    @Schema(description = "Password Retry Interval")
    private Integer passwordRetryInterval;

    @Schema(description = "Password Retry Limit")
    private Integer passwordRetryLimit;

    @Schema(description = "Password Complexity Prompt")
    private String passwordComplexityPrompt;

    @Schema(description = "Passwords Not Duplicate")
    private Integer passwordNotDuplicate;

    @Schema(description = "Minimum Character Length")
    private Integer minLength;

    @Schema(description = "Minimum Lowercase Characters")
    private Integer minLowercase;

    @Schema(description = "Minimum Uppercase Characters")
    private Integer minUppercase;

    @Schema(description = "Minimum Digits")
    private Integer minDigits;

    @Schema(description = "Minimum Modified Characters")
    private Integer minModifiedChars;

    @Schema(description = "Minimum Special Characters")
    private Integer minSpecialChars;

    @Schema(description = "Active")
    private Boolean active;
}