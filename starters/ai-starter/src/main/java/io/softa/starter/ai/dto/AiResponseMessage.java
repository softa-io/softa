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
    private Long conversationId;

    @Schema(description = "User Message ID")
    private Long userMessageId;

    @Schema(description = "AI Message ID")
    private Long aiMessageId;

}
