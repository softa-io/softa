package io.softa.starter.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Apple login request parameters
 */
@Data
@Schema(description = "Apple Login DTO")
public class AppleLoginDTO {
    @NotBlank
    @Schema(description = "Apple ID Token", requiredMode = Schema.RequiredMode.REQUIRED)
    private String token;

    @Schema(description = "Apple authorization code")
    private String code;

    @Schema(description = "Apple state")
    private String state;

    @Schema(description = "Apple user info returned only on the first authorization")
    private User user;

    @Data
    public static class User {
        @Schema(description = "Email")
        private String email;
        @Schema(description = "User Name")
        private Name name;
    }

    @Data
    public static class Name {
        @Schema(description = "First name")
        private String firstName;
        @Schema(description = "Last name")
        private String lastName;
    }
}