package io.softa.starter.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Unlock account request payload.
 */
@Data
@Schema(description = "Unlock user account request")
public class UnlockAccountDTO {

    @Schema(description = "Unlock reason")
    private String reason;
}

