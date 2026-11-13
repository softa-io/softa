package io.softa.starter.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Request body for {@code POST /login/verifyJoinCode}. */
@Data
public class VerifyJoinCodeDTO {

    @NotBlank(message = "Token cannot be empty!")
    private String token;

    @NotBlank(message = "Channel cannot be empty!")
    private String channel;

    @NotBlank(message = "Verification code cannot be empty!")
    private String verificationCode;
}
