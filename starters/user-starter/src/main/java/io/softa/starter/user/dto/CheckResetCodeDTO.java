package io.softa.starter.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** A password-reset code to check before the new password is asked for. */
@Data
public class CheckResetCodeDTO {

    @NotBlank(message = "Account cannot be empty!")
    private String identifier;

    @NotBlank(message = "Verification code cannot be empty!")
    private String verificationCode;
}
