package io.softa.starter.ai.service.impl;

import com.plexpt.chatgpt.entity.chat.ChatCompletion;
import com.plexpt.chatgpt.entity.chat.ChatCompletionResponse;
import com.plexpt.chatgpt.entity.chat.Message;
import com.plexpt.chatgpt.entity.chat.StreamOption;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.ai.adapter.AiAdapter;
import io.softa.starter.ai.adapter.AiAdapterFactory;
import io.softa.starter.ai.dto.AiContent;
import io.softa.starter.ai.dto.AiResponseMessage;
import io.softa.starter.ai.dto.AiStreamRequest;
import io.softa.starter.ai.dto.AiUserMessage;
import io.softa.starter.ai.entity.AiMessage;
import io.softa.starter.ai.entity.AiRobot;
import io.softa.starter.ai.listener.StreamResponseListener;
import io.softa.starter.ai.service.AiConversationService;
import io.softa.starter.ai.service.AiMessageService;
import io.softa.starter.ai.service.AiRobotService;

/**
 * AiRobot Model Service Implementation
 */
@Service
@Slf4j
public class AiRobotServiceImpl extends EntityServiceImpl<AiRobot, String> implements AiRobotService {

    @Value("${ai.response.timeout:60000}")
    private Long timeout;

    @Autowired
    private AiAdapterFactory aiAdapterFactory;

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private AiMessageService aiMessageService;

    /**
     * Persist user message and ai message in advance for stream response.
     * Response: Conversation ID, User Message ID, AI Message ID
     *
     * @param aiUserMessage AI User message
     * @return AiResponseMessage
     */
    @Override
    public AiResponseMessage persistChatMessage(AiUserMessage aiUserMessage) {
        if (aiUserMessage.getConversationId() == null) {
            String conversationId = conversationService.newConversation(aiUserMessage);
            aiUserMessage.setConversationId(conversationId);
        }
        // Save user message
        AiMessage userMessage = aiMessageService.saveUserMessage(aiUserMessage);
        // Save AI message in advance for stream response
        AiMessage aiMessage = aiMessageService.SaveAiMessageForStream(userMessage);
        // Build response message
        AiResponseMessage aiResponseMessage = new AiResponseMessage();
        aiResponseMessage.setConversationId(userMessage.getConversationId());
        aiResponseMessage.setUserMessageId(userMessage.getId());
        aiResponseMessage.setAiMessageId(aiMessage.getId());
        return aiResponseMessage;
    }

    /**
     * Get robot object based on robot ID
     *
     * @param robotId Robot ID
     * @return Robot object
     */
    private AiRobot getAiRobotById(String robotId) {
        Assert.notBlank(robotId, "Robot ID cannot be empty!");
        return this.getById(robotId).orElseThrow(
                () -> new IllegalArgumentException("Robot ID not exists: " + robotId));
    }

    /**
     * Get robot object based on robot code
     *
     * @param robotCode Robot code
     * @return Robot object
     */
    private AiRobot getAiRobotByCode(String robotCode) {
        Assert.notBlank(robotCode, "Robot code cannot be empty!");
        Filters filters = new Filters().eq(AiRobot::getCode, robotCode);
        return this.searchOne(filters).orElseThrow(
                () -> new IllegalArgumentException("Robot code not exists: " + robotCode));
    }

    /**
     * Build ChatCompletion object
     *
     * @param aiRobot Robot object
     * @param content Content
     * @return ChatCompletion
     */
    private ChatCompletion buildChatCompletion(AiRobot aiRobot, String content) {
        // [System message, User message]
        Message systemMessage = Message.ofSystem(aiRobot.getSystemPrompt());
        Message userMessage = Message.of(content);
        ChatCompletion.ChatCompletionBuilder builder = ChatCompletion.builder()
                .model(aiRobot.getAiModel())
                .messages(Arrays.asList(systemMessage, userMessage))
                .temperature(aiRobot.getTemperature());
        if (Boolean.TRUE.equals(aiRobot.getStream())) {
            builder.stream(true).streamOptions(new StreamOption(true));
        }
        if (aiRobot.getOutputTokensLimit() != null && aiRobot.getOutputTokensLimit() > 0) {
            builder.maxTokens(aiRobot.getOutputTokensLimit());
        }
        return builder.build();
    }

    /**
     * Stream Chat
     *
     * @param aiRequest AI Stream Request Message
     * @return SseEmitter
     */
    @Override
    public SseEmitter streamChat(AiStreamRequest aiRequest) {
        Context context = ContextHolder.getContext();
        AiMessage userMessage = aiMessageService.getById(aiRequest.getUserMessageId()).orElseThrow(
                () -> new IllegalArgumentException("User Message ID not exists: " + aiRequest.getUserMessageId()));
        AiMessage aiMessage = aiMessageService.getById(aiRequest.getAiMessageId()).orElseThrow(
                () -> new IllegalArgumentException("AI Message ID not exists: " + aiRequest.getAiMessageId()));
        AiRobot aiRobot = this.getAiRobotById(userMessage.getRobotId());
        ChatCompletion chatCompletion = this.buildChatCompletion(aiRobot, userMessage.getContent());
        AiAdapter aiAdapter = aiAdapterFactory.getAiAdapter(aiRobot.getAiProvider());

        // Use SseEmitter to send messages to the client
        SseEmitter sseEmitter = new SseEmitter(timeout);
        // Close sseEmitter on timeout
        sseEmitter.onTimeout(sseEmitter::complete);
        // Close sseEmitter on error
        sseEmitter.onError(_ -> sseEmitter.complete());

        // Listen for LLM response through SseStreamListener
        StreamResponseListener responseListener = new StreamResponseListener(sseEmitter);
        responseListener.setOnComplete(aiContent -> {
            // Close the SSE connection to the client
            sseEmitter.complete();
            // Update the AI message content and status
            ContextHolder.runWith(context, () -> aiMessageService.updateAiMessageAfterStream(aiMessage.getId(), aiContent, userMessage.getId()));
        });
        // Send the chat message
        aiAdapter.streamChat(chatCompletion, responseListener);
        return sseEmitter;
    }

    /**
     * Chat API
     *
     * @param aiUserMessage Chat message
     * @return AiMessage
     */
    @Override
    public AiMessage chat(AiUserMessage aiUserMessage) {
        // Get robot object
        AiRobot aiRobot = this.getAiRobotById(aiUserMessage.getRobotId());
        // New conversation if conversation ID is not set
        if (aiUserMessage.getConversationId() == null) {
            String conversationId = conversationService.newConversation(aiUserMessage);
            aiUserMessage.setConversationId(conversationId);
        }
        // Complete chat
        return this.completeChat(aiRobot, aiUserMessage);
    }

    /**
     * Complete chat
     *
     * @param aiRobot      Robot object
     * @param aiUserMessage AI User message
     * @return AI message
     */
    private AiMessage completeChat(AiRobot aiRobot, AiUserMessage aiUserMessage) {
        // Save user message
        AiMessage userMessage = aiMessageService.saveUserMessage(aiUserMessage);
        ChatCompletion chatCompletion = this.buildChatCompletion(aiRobot, aiUserMessage.getContent());
        AiAdapter aiAdapter = aiAdapterFactory.getAiAdapter(aiRobot.getAiProvider());

        // Send the chat message
        ChatCompletionResponse chatResponse = aiAdapter.chat(chatCompletion);
        // Save the chat message
        String answer = chatResponse.getChoices().getFirst().getMessage().getContent();
        AiContent aiContent = new AiContent(false);
        aiContent.append(answer);
        aiContent.setUsage(chatResponse.getUsage());
        return aiMessageService.saveAiMessageForNonStreaming(userMessage, answer, chatResponse.getUsage());
    }

    /**
     * One-time chat
     *
     * @param robotCode    Robot code
     * @param message  User message
     * @return AI response message
     */
    @Override
    public String oneTimeChat(String robotCode, String message) {
        AiRobot aiRobot = this.getAiRobotByCode(robotCode);
        // Build AI user message
        AiUserMessage aiUserMessage = new AiUserMessage();
        aiUserMessage.setRobotId(aiRobot.getId());
        aiUserMessage.setContent(message);
        // Complete chat
        AiMessage aiMessage = this.completeChat(aiRobot, aiUserMessage);
        return aiMessage.getContent();
    }
}