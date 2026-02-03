package io.softa.starter.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI Stream Request Message
 */
@Data
@Schema(name = "AI Stream Request")
public class AiStreamRequest {

    @Schema(description = "User Message ID")
    @NotBlank(message = "User Message ID cannot be empty!")
    private String userMessageId;

    @Schema(description = "AI Message ID")
    @NotBlank(message = "AI Message ID cannot be empty!")
    private String aiMessageId;

}
