package io.softa.starter.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MobileCodeDTO {
    @NotBlank(message = "Mobile cannot be empty!")
    private String mobile;

    @NotBlank(message = "Verification code cannot be empty!")
    private String code;
}
