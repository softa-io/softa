package io.softa.starter.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ForgotPasswordDTO {
    @Email(message = "Email format is incorrect!")
    @NotBlank(message = "Email cannot be empty!")
    private String email;
}
