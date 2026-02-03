package io.softa.starter.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * AI Response Message
 */
@Data
@Schema(name = "AI Response Message")
public class AiResponseMessage {

    @Schema(description = "Conversation ID")
    private String conversationId;

    @Schema(description = "User Message ID")
    private String userMessageId;

    @Schema(description = "AI Message ID")
    private String aiMessageId;

}
