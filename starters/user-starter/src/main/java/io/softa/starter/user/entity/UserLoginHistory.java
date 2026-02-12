package io.softa.starter.user.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.user.enums.LoginDeviceType;
import io.softa.starter.user.enums.LoginMethod;
import io.softa.starter.user.enums.LoginStatus;

/**
 * UserLoginHistory Model
 */
@Data
@Schema(name = "UserLoginHistory")
@EqualsAndHashCode(callSuper = true)
public class UserLoginHistory extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "User ID")
    private Long userId;

    @Schema(description = "Login Method")
    private LoginMethod loginMethod;

    @Schema(description = "Login Device Type")
    private LoginDeviceType loginDeviceType;

    @Schema(description = "IP Address")
    private String ipAddress;

    @Schema(description = "User Agent")
    private String userAgent;

    @Schema(description = "Location")
    private String location;

    @Schema(description = "Status")
    private LoginStatus status;
}