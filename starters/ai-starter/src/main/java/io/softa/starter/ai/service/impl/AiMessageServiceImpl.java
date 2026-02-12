package io.softa.starter.ai.service.impl;

import java.util.HashMap;
import java.util.Map;
import com.plexpt.chatgpt.entity.billing.Usage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.orm.utils.LambdaUtils;
import io.softa.starter.ai.dto.AiContent;
import io.softa.starter.ai.dto.AiUserMessage;
import io.softa.starter.ai.entity.AiMessage;
import io.softa.starter.ai.enums.AiMessageRole;
import io.softa.starter.ai.enums.AiMessageStatus;
import io.softa.starter.ai.service.AiMessageService;

/**
 * AiMessage Model Service Implementation
 */
@Service
public class AiMessageServiceImpl extends EntityServiceImpl<AiMessage, Long> implements AiMessageService {

    /**
     * Save user request message.
     *
     * @param aiUserMessage User request message
     * @return User request message
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMessage saveUserMessage(AiUserMessage aiUserMessage) {
        AiMessage aiMessage = new AiMessage();
        aiMessage.setRole(AiMessageRole.USER);
        aiMessage.setRobotId(aiUserMessage.getRobotId());
        aiMessage.setConversationId(aiUserMessage.getConversationId());
        aiMessage.setParentId(aiUserMessage.getParentId());
        aiMessage.setContent(aiUserMessage.getContent());
        aiMessage.setStatus(AiMessageStatus.COMPLETED);
        aiMessage.setId(this.createOne(aiMessage));
        return aiMessage;
    }

    /**
     * Save AI response message for non-streaming response.
     *
     * @param userMessage User message
     * @param answer      AI response content
     * @param usage       Usage
     * @return AI response message
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMessage saveAiMessageForNonStreaming(AiMessage userMessage, String answer, Usage usage) {
        AiMessage aiMessage = new AiMessage();
        aiMessage.setRole(AiMessageRole.ASSISTANT);
        aiMessage.setRobotId(userMessage.getRobotId());
        aiMessage.setConversationId(userMessage.getConversationId());
        aiMessage.setParentId(userMessage.getId());
        aiMessage.setContent(answer);
        aiMessage.setTokens(usage == null ? 0 : Math.toIntExact(usage.getCompletionTokens()));
        aiMessage.setStream(false);
        aiMessage.setStatus(AiMessageStatus.COMPLETED);
        aiMessage.setId(this.createOne(aiMessage));
        // update parent message token usage
        this.updateParentMessageTokenUsage(userMessage.getId(), usage);
        return aiMessage;
    }

    /**
     * Update parent message token usage.
     *
     * @param parentId Parent message ID
     * @param usage    Usage
     */
    private void updateParentMessageTokenUsage(Long parentId, Usage usage) {
        Map<String, Object> userMessageMap = new HashMap<>();
        userMessageMap.put(ModelConstant.ID, parentId);
        String tokenField = LambdaUtils.getAttributeName(AiMessage::getTokens);
        Integer promptTokens = usage == null ? 0 : Math.toIntExact(usage.getPromptTokens());
        userMessageMap.put(tokenField, promptTokens);
        this.modelService.updateOne(modelName, userMessageMap);
    }

    /**
     * Save AI response message for stream response.
     *
     * @param userMessage User message
     * @return AI message with initial status
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMessage SaveAiMessageForStream(AiMessage userMessage) {
        AiMessage aiMessage = new AiMessage();
        aiMessage.setRole(AiMessageRole.ASSISTANT);
        aiMessage.setRobotId(userMessage.getRobotId());
        aiMessage.setConversationId(userMessage.getConversationId());
        aiMessage.setParentId(userMessage.getId());
        aiMessage.setStream(true);
        aiMessage.setStatus(AiMessageStatus.PENDING);
        aiMessage.setId(this.createOne(aiMessage));
        return aiMessage;
    }

    /**
     * Update AI message after stream completion.
     *
     * @param aiMessageId   AI message ID
     * @param aiContent     AI response content
     * @param userMessageId User message ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAiMessageAfterStream(Long aiMessageId, AiContent aiContent, Long userMessageId) {
        Usage usage = aiContent.getUsage();
        Map<String, Object> updateMap = new HashMap<>();
        updateMap.put(ModelConstant.ID, aiMessageId);
        updateMap.put(LambdaUtils.getAttributeName(AiMessage::getContent), aiContent.getContent().toString());
        updateMap.put(LambdaUtils.getAttributeName(AiMessage::getTokens),
                usage == null ? 0 : Math.toIntExact(usage.getCompletionTokens()));
        updateMap.put(LambdaUtils.getAttributeName(AiMessage::getStatus), AiMessageStatus.COMPLETED);

        this.modelService.updateOne(modelName, updateMap);

        // Update parent message token usage
        this.updateParentMessageTokenUsage(userMessageId, usage);
    }
}