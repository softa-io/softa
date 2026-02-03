package io.softa.starter.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * LinkedIn OAuth Token Response DTO
 */
@Data
public class LinkedInTokenResponseDTO {

    /**
     * Access token
     */
    @JsonProperty("access_token")
    private String accessToken;

    /**
     * Token type
     */
    @JsonProperty("token_type")
    private String tokenType;

    /**
     * Expires in seconds
     */
    @JsonProperty("expires_in")
    private Integer expiresIn;

    /**
     * Access scope of the token
     */
    @JsonProperty("scope")
    private String scope;
}