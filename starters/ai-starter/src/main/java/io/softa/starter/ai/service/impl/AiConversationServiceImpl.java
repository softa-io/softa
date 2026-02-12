package io.softa.starter.ai.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.ai.dto.AiUserMessage;
import io.softa.starter.ai.entity.AiConversation;
import io.softa.starter.ai.service.AiConversationService;

/**
 * AiConversation Model Service Implementation
 */
@Service
public class AiConversationServiceImpl extends EntityServiceImpl<AiConversation, Long> implements AiConversationService {

    /**
     * New conversation
     *
     * @param aiUserMessage AI User Message
     * @return Conversation ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long newConversation(AiUserMessage aiUserMessage) {
        AiConversation conversation = new AiConversation();
        String content = aiUserMessage.getContent();
        conversation.setTitle(content.length() > 10 ? content.substring(0, 10) : content);
        conversation.setRobotId(aiUserMessage.getRobotId());
        return this.createOne(conversation);
    }

}