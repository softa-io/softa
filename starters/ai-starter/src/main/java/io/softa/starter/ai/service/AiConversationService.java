package io.softa.starter.ai.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.ai.dto.AiUserMessage;
import io.softa.starter.ai.entity.AiConversation;

/**
 * AiConversation Model Service Interface
 */
public interface AiConversationService extends EntityService<AiConversation, Long> {

    /**
     * New conversation
     *
     * @param aiUserMessage AI User Message
     * @return Conversation ID
     */
    Long newConversation(AiUserMessage aiUserMessage);

}