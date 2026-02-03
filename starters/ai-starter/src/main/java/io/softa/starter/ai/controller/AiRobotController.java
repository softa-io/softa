package io.softa.starter.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.ai.dto.AiResponseMessage;
import io.softa.starter.ai.dto.AiStreamRequest;
import io.softa.starter.ai.dto.AiUserMessage;
import io.softa.starter.ai.entity.AiMessage;
import io.softa.starter.ai.entity.AiRobot;
import io.softa.starter.ai.service.AiRobotService;

/**
 * AiRobot Model Controller
 */
@Tag(name = "AiRobot")
@RestController
@RequestMapping("/AiRobot")
public class AiRobotController extends EntityController<AiRobotService, AiRobot, String> {

    /**
     * Persist user message and AI message in advance for stream response.
     * Response: Conversation ID, User Message ID, AI Message ID
     *
     * @param aiUserMessage AI User Message
     * @return AiResponseMessage
     */
    @Operation(summary = "Persist Chat Message", description = "Response: Conversation ID, User Message ID, AI Message ID")
    @PostMapping(value = "/persistChatMessage")
    public ApiResponse<AiResponseMessage> persistChatMessage(@RequestBody @Valid AiUserMessage aiUserMessage) {
        AiResponseMessage aiResponseMessage = service.persistChatMessage(aiUserMessage);
        return ApiResponse.success(aiResponseMessage);
    }

    /**
     * Stream Chat
     * Response using SSE(Server-Sent Events) with JSON format data, so
     * `application/json` is the default content type.
     *
     * @param aiStreamRequest AI Stream Request
     * @return SseEmitter with JSON format data
     */
    @Operation(summary = "Stream Chat", description = "Stream chat by SSE(Server-Sent Events).")
    @PostMapping(value = "/streamChat", produces = "text/event-stream")
    public SseEmitter streamChat(@RequestBody @Valid AiStreamRequest aiStreamRequest) {
        return service.streamChat(aiStreamRequest);
    }

    /**
     * Non-streaming Chat API, return the answer directly
     *
     * @param aiUserMessage AI User Message
     * @return AiMessage
     */
    @Operation(summary = "Chat API", description = "Non-streaming Chat API")
    @PostMapping(value = "/chat")
    public ApiResponse<AiMessage> chat(@RequestBody @Valid AiUserMessage aiUserMessage) {
        AiMessage aiMessage = service.chat(aiUserMessage);
        return ApiResponse.success(aiMessage);
    }

}