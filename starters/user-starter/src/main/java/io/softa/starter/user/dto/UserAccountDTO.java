package io.softa.starter.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * User account DTO
 * Focus on account basic information and authentication related data
 */
@Data
@Schema(description = "User account DTO")
public class UserAccountDTO {

    @Schema(description = "Email")
    private String email;

    @Schema(description = "Mobile")
    private String mobile;

    @Schema(description = "Username")
    private String username;

    @Schema(description = "Nickname")
    private String nickname;

    /**
     * Create social account registration DTO
     */
    public static UserAccountDTO forSocialRegistration(String username, String nickname) {
        UserAccountDTO dto = new UserAccountDTO();
        dto.setUsername(username);
        dto.setNickname(nickname);
        return dto;
    }

}