package io.softa.starter.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import io.softa.starter.user.enums.OAuthProvider;

/**
 * OAuth Credential
 */
@Data
@Schema(description = "OAuth Credential")
public class OAuthCredential {

    @NotNull
    @Schema(description = "OAuth Provider", requiredMode = Schema.RequiredMode.REQUIRED)
    private OAuthProvider provider;

    @NotBlank
    @Schema(description = "OAuth Code", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @NotBlank
    @Schema(description = "Redirect URI", requiredMode = Schema.RequiredMode.REQUIRED)
    private String redirectUri;

    @Schema(description = "PKCE Code Verifier", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String codeVerifier;
}