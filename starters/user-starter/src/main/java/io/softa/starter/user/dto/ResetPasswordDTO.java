package io.softa.starter.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ResetPasswordDTO {
    // The token is sent to the user's email address when the user forgot the password
    @NotBlank(message = "Reset password token cannot be empty!")
    private String token;

    @NotBlank(message = "New password cannot be empty!")
    private String newPassword;
}
