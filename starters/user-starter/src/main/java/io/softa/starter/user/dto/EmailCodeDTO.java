package io.softa.starter.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EmailCodeDTO {
    @Email(message = "Email format is incorrect!")
    @NotBlank(message = "Email cannot be empty!")
    private String email;

    @NotBlank(message = "Verification code cannot be empty!")
    private String verificationCode;
}
