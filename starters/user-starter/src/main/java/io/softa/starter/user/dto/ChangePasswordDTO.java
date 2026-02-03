package io.softa.starter.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChangePasswordDTO {

    @NotBlank(message = "Current password cannot be empty!")
    private String currentPassword;

    @NotBlank(message = "New password cannot be empty!")
    private String newPassword;
}
