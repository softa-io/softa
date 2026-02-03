package io.softa.starter.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * LinkedIn User Info DTO
 * Based on the standard fields defined by LinkedIn for OpenID Connect.
 */
@Data
public class LinkedInUserInfoDTO {

    /**
     * Unique id of the LinkedIn user (subject)
     */
    @JsonProperty("sub")
    private String sub;

    /**
     * The full name of the user
     */
    @JsonProperty("name")
    private String name;

    /**
     * The given name (first name) of the user
     */
    @JsonProperty("given_name")
    private String givenName;

    /**
     * The family name (last name) of the user
     */
    @JsonProperty("family_name")
    private String familyName;

    /**
     * The public profile picture URL of the user
     */
    @JsonProperty("picture")
    private String picture;

    /**
     * The locale info of the user
     */
    @JsonProperty("locale")
    private Object locale;

    /**
     * The optional email of the user
     */
    @JsonProperty("email")
    private String email;

    /**
     * Whether the email is verified
     */
    @JsonProperty("email_verified")
    private Boolean emailVerified;

}