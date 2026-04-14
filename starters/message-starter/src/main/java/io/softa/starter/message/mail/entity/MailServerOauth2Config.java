package io.softa.starter.message.mail.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.mail.enums.OAuthProvider;
import io.softa.starter.message.mail.enums.ServerType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * OAuth2 credentials for a mail server config.
 */
@Data
@Schema(name = "MailServerOauth2Config")
@EqualsAndHashCode(callSuper = true)
public class MailServerOauth2Config extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "FK to mail_send_server_config.id or mail_receive_server_config.id")
    private Long serverConfigId;

    @Schema(description = "Whether this credential belongs to a sending or receiving server config")
    private ServerType serverType;

    @Schema(description = "OAuth2 provider")
    private OAuthProvider provider;

    @Schema(description = "OAuth2 client ID")
    private String clientId;

    @Schema(description = "OAuth2 client secret")
    private String clientSecret;

    @Schema(description = "Azure / Microsoft tenant ID (for Microsoft provider)")
    private String azureTenantId;

    @Schema(description = "OAuth2 scopes (space-separated)")
    private String scope;

    @Schema(description = "Authorization endpoint URL")
    private String authorizationEndpoint;

    @Schema(description = "Token endpoint URL")
    private String tokenEndpoint;

    @Schema(description = "Redirect URI registered with the provider")
    private String redirectUri;
}
