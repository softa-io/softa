package io.softa.starter.message.mail.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * OAuth2 access/refresh token storage per mail account.
 */
@Data
@Schema(name = "MailServerOauth2Token")
@EqualsAndHashCode(callSuper = true)
public class MailServerOauth2Token extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "FK to mail_server_oauth2_config.id")
    private Long serverConfigId;

    @Schema(description = "Email account identifier (usually the email address)")
    private String accountIdentifier;

    @Schema(description = "Access token")
    private String accessToken;

    @Schema(description = "Refresh token")
    private String refreshToken;

    @Schema(description = "Access token expiry time")
    private LocalDateTime accessTokenExpiry;

    @Schema(description = "Refresh token expiry time")
    private LocalDateTime refreshTokenExpiry;
}
