package io.softa.starter.ai.service;

import com.plexpt.chatgpt.entity.billing.Usage;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.ai.dto.AiContent;
import io.softa.starter.ai.dto.AiUserMessage;
import io.softa.starter.ai.entity.AiMessage;

/**
 * AiMessage Model Service Interface
 */
public interface AiMessageService extends EntityService<AiMessage, String> {

    /**
     * Save user request message.
     *
     * @param aiUserMessage User request message
     * @return User request message
     */
    AiMessage saveUserMessage(AiUserMessage aiUserMessage);

    /**
     * Save AI response message for stream response.
     *
     * @param userMessage User message
     * @return AI message with initial status
     */
    AiMessage SaveAiMessageForStream(AiMessage userMessage);

    /**
     * Update AI message after stream completion.
     *
     * @param aiMessageId   AI message ID
     * @param aiContent     AI response content
     * @param userMessageId User message ID
     */
    void updateAiMessageAfterStream(String aiMessageId, AiContent aiContent, String userMessageId);

    /**
     * Save AI response message for non-streaming response.
     *
     * @param userMessage User message
     * @param answer      AI response content
     * @param usage       Usage
     * @return AI response message
     */
    AiMessage saveAiMessageForNonStreaming(AiMessage userMessage, String answer, Usage usage);

}