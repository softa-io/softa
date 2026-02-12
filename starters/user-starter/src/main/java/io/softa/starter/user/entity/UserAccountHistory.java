package io.softa.starter.user.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * UserAccountHistory Model
 */
@Data
@Schema(name = "UserAccountHistory")
@EqualsAndHashCode(callSuper = true)
public class UserAccountHistory extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "User ID")
    private Long userId;

    @Schema(description = "Password")
    private String password;

    @Schema(description = "Password Salt")
    private String passwordSalt;
}