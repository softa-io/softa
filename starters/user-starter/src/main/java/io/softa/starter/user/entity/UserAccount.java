package io.softa.starter.user.entity;

import io.softa.framework.orm.entity.AuditableModel;
import java.io.Serial;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.starter.user.enums.AccountStatus;

/**
 * UserAccount Model
 */
@Data
@Schema(name = "UserAccount")
@EqualsAndHashCode(callSuper = true)
public class UserAccount extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private String id;

    @Schema(description = "Tenant ID")
    private String tenantId;

    @Schema(description = "Nickname")
    private String nickname;

    @Schema(description = "Username")
    private String username;

    @Schema(description = "Password")
    private String password;

    @Schema(description = "Password Salt")
    private String passwordSalt;

    @Schema(description = "email")
    private String email;

    @Schema(description = "Mobile")
    private String mobile;

    @Schema(description = "Activation Time")
    private LocalDateTime activationTime;

    @Schema(description = "Policy ID")
    private String policyId;

    @Schema(description = "Locked")
    private Boolean locked;

    @Schema(description = "Status")
    private AccountStatus status;
}