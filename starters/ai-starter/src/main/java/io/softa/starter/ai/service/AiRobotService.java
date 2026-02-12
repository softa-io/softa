package io.softa.starter.ai.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.ai.dto.AiResponseMessage;
import io.softa.starter.ai.dto.AiStreamRequest;
import io.softa.starter.ai.dto.AiUserMessage;
import io.softa.starter.ai.entity.AiMessage;
import io.softa.starter.ai.entity.AiRobot;

/**
 * AiRobot Model Service Interface
 */
public interface AiRobotService extends EntityService<AiRobot, Long> {

    /**
     * Persist user message and ai message in advance for stream response.
     * Response: Conversation ID, User Message ID, AI Message ID
     *
     * @param aiUserMessage AI User message
     * @return AiResponseMessage
     */
    AiResponseMessage persistChatMessage(AiUserMessage aiUserMessage);

    /**
     * Stream Chat
     *
     * @param aiStreamRequest AI Stream Request
     * @return SseEmitter
     */
    SseEmitter streamChat(AiStreamRequest aiStreamRequest);

    /**
     * Chat API
     *
     * @param aiUserMessage AI User message
     * @return AiMessage
     */
    AiMessage chat(AiUserMessage aiUserMessage);

    /**
     * One-time chat
     *
     * @param robotCode    Robot code
     * @param userMessage  User message
     * @return AI response message
     */
    String oneTimeChat(String robotCode, String userMessage);
}