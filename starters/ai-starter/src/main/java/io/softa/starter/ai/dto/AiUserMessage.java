package io.softa.starter.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI Chat User Message
 */
@Data
@Schema(name = "AI User Message")
public class AiUserMessage {

    @Schema(description = "Robot ID")
    @NotBlank(message = "Robot ID cannot be empty!")
    private String robotId;

    @Schema(description = "Chat Content")
    @NotBlank(message = "Chat content cannot be empty!")
    private String content;

    @Schema(description = "Conversation ID")
    private String conversationId;

    @Schema(description = "Parent Message ID")
    private String parentId;

}
