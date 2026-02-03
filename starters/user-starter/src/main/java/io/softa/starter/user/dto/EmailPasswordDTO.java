package io.softa.starter.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EmailPasswordDTO {

    @NotBlank(message = "Email cannot be empty!")
    private String email;

    @NotBlank(message = "Password cannot be empty!")
    private String password;
}
