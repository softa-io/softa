package io.softa.starter.ai.listener;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import com.plexpt.chatgpt.entity.chat.ChatChoice;
import com.plexpt.chatgpt.entity.chat.ChatCompletionResponse;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.softa.framework.base.exception.IntegrationException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.ai.constant.AiConstant;
import io.softa.starter.ai.dto.AiContent;

/**
 * Stream Response Listener
 */
@Slf4j
@RequiredArgsConstructor
public class StreamResponseListener extends EventSourceListener {

    final SseEmitter sseEmitter;

    // Merge streamed message for passing to the callback function for persistent processing
    private final AiContent aiContent = new AiContent(true);

    // Callback function for stream completion
    @Setter
    @Getter
    protected Consumer<AiContent> onComplete = s -> {};

    @Override
    public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
        // do nothing
    }

    @Override
    public void onClosed(@NotNull EventSource eventSource) {
        // do nothing
    }

    /**
     * Stream response event
     * @param eventSource EventSource
     * @param id Id
     * @param type Type
     * @param data Data
     */
    @Override
    public void onEvent(@NotNull EventSource eventSource, String id, String type, String data) {
        if (data.equals(AiConstant.STREAM_END_MESSAGE)) {
            try {
                // Send stream end message to client
                sseEmitter.send(AiConstant.STREAM_END_MESSAGE);
            } catch (IOException e) {
                log.warn("Failed to send stream end message to client: {}", e.getMessage());
                // Continue to execute the subsequent logic
            }
            // Finish the stream and send the merged message to the listener callback method.
            onComplete.accept(aiContent);
            // Release eventSource resource
            eventSource.cancel();
            return;
        }
        // Parse Json data to ChatCompletionResponse
        ChatCompletionResponse response = JsonUtils.stringToObject(data, ChatCompletionResponse.class);
        if (response.getUsage() != null) {
            aiContent.setUsage(response.getUsage());
        }
        List<ChatChoice> chatChoices = response.getChoices();
        if (chatChoices == null || chatChoices.isEmpty()) {
            return;
        }
        // Extract the message content from choices[0].delta.content and send it to the frontend.
        String content = chatChoices.getFirst().getDelta().getContent();
        if (content != null) {
            aiContent.append(content);
            try {
                // Use \u001A to replace \n, avoiding SSE protocol breaking format
                String contentToSend = content.replace('\n', '\u001A');
                sseEmitter.send(contentToSend);
            } catch (IOException e) {
                throw new IntegrationException(e.getMessage(), e);
            }
        }
    }

    /**
     * Failure
     * @param eventSource EventSource
     * @param throwable Throwable
     * @param response Response
     */
    @Override
    public void onFailure(@NotNull EventSource eventSource, Throwable throwable, Response response) {
        try {
            log.error("Stream connection or response exception!", throwable);
            if (response != null) {
                log.error("response body: {}", response.body());
            }
            // Close sseEmitter
            sseEmitter.complete();
        } catch (Exception e) {
            log.error("Exception in SSE failure handling!", e);
        } finally {
            // Release eventSource resource
            eventSource.cancel();
        }
    }
}
