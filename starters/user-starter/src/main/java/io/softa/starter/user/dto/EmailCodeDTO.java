package io.softa.starter.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EmailCodeDTO {
    @NotBlank(message = "Email cannot be empty!")
    private String email;

    @NotBlank(message = "Verification code cannot be empty!")
    private String code;
}
