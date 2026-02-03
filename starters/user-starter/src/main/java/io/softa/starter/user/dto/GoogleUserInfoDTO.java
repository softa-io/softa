package io.softa.starter.user.dto;

import com.nimbusds.jwt.JWTClaimsSet;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Google User Info DTO
 */
@Data
@Slf4j
public class GoogleUserInfoDTO {

    /**
     * Unique id of the Google user (subject)
     */
    private String id;

    /**
     * User's email address
     */
    private String email;

    /**
     * Whether the email is verified
     */
    private Boolean emailVerified;

    /**
     * User's full name
     */
    private String name;

    /**
     * User's profile picture URL
     */
    private String picture;

    /**
     * The given name (first name) of the user
     */
    private String givenName;

    /**
     * The family name (last name) of the user
     */
    private String familyName;

    /**
     * New GoogleUserInfoDTO from JWT claims
     * @param claims JWT claims
     * @return GoogleUserInfoDTO
     */
    public static GoogleUserInfoDTO fromClaims(JWTClaimsSet claims) {
        GoogleUserInfoDTO dto = new GoogleUserInfoDTO();
        try {
            dto.setId(claims.getSubject());
            dto.setEmail(claims.getStringClaim("email"));
            dto.setEmailVerified(claims.getBooleanClaim("email_verified"));
            dto.setName(claims.getStringClaim("name"));
            dto.setPicture(claims.getStringClaim("picture"));
            dto.setGivenName(claims.getStringClaim("given_name"));
            dto.setFamilyName(claims.getStringClaim("family_name"));
        } catch (Exception e) {
            log.warn("Failed to extract some claims from Google ID token", e);
        }
        return dto;
    }
}