package io.softa.starter.user.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.user.enums.OAuthProvider;

/**
 * UserAuthProvider Model
 */
@Data
@Schema(name = "UserAuthProvider")
@EqualsAndHashCode(callSuper = true)
public class UserAuthProvider extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private String id;

    @Schema(description = "Tenant ID")
    private String tenantId;

    @Schema(description = "User ID")
    private String userId;

    @Schema(description = "Provider")
    private OAuthProvider provider;

    @Schema(description = "Provider User ID")
    private String providerUserId;

    @Schema(description = "Additional Info")
    private String additionalInfo;
}