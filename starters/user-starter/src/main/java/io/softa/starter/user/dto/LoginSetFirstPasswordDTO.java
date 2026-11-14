package io.softa.starter.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * {@code POST /login/setFirstPassword}: the first password, set with the sign-in token before any
 * company is entered.
 */
@Data
public class LoginSetFirstPasswordDTO {

    @NotBlank(message = "The sign-in token is required.")
    private String authToken;

    @NotBlank(message = "New password cannot be empty!")
    private String newPassword;
}
