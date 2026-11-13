package io.softa.starter.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * A code-based password reset. {@code identifier} is an email OR a dial-code mobile — the same
 * login identifier the sign-in form accepts, so a mobile-only employee can reset too.
 */
@Data
public class ResetPasswordByCodeDTO {

    @NotBlank(message = "Account cannot be empty!")
    private String identifier;

    @NotBlank(message = "Verification code cannot be empty!")
    private String verificationCode;

    @NotBlank(message = "New password cannot be empty!")
    private String newPassword;
}
