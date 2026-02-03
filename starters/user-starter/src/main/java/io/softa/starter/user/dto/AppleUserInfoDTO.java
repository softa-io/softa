package io.softa.starter.user.dto;

import com.nimbusds.jwt.JWTClaimsSet;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Apple User Info DTO
 */
@Data
@Slf4j
public class AppleUserInfoDTO {

    /**
     * Unique id of the Apple user (subject)
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
     * Whether the email is a private relay email
     */
    private Boolean isPrivateEmail;

    /**
     * Real user status (0: unknown, 1: likely real, 2: likely not real)
     */
    private Integer realUserStatus;

    /**
     * New AppleUserInfoDTO from JWT claims
     * @param claims JWT claims
     * @return AppleUserInfoDTO
     */
    public static AppleUserInfoDTO fromClaims(JWTClaimsSet claims) {
        AppleUserInfoDTO dto = new AppleUserInfoDTO();
        try {
            dto.setId(claims.getSubject());
            dto.setEmail(claims.getStringClaim("email"));
            dto.setEmailVerified(claims.getBooleanClaim("email_verified"));
            dto.setIsPrivateEmail(claims.getBooleanClaim("is_private_email"));
            dto.setRealUserStatus(claims.getIntegerClaim("real_user_status"));
        } catch (Exception e) {
            log.warn("Failed to extract some claims from Apple ID token", e);
        }
        return dto;
    }
}