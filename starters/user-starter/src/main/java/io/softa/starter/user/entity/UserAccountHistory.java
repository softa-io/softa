package io.softa.starter.user.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.io.Serial;

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
    private String id;

    @Schema(description = "Tenant ID")
    private String tenantId;

    @Schema(description = "User ID")
    private String userId;

    @Schema(description = "Password")
    private String password;

    @Schema(description = "Password Salt")
    private String passwordSalt;
}