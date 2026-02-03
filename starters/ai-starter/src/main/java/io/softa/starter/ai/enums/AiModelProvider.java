package io.softa.starter.ai.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AiModelProvider {
    OPEN_AI("OpenAI", "OpenAI"),
    DEEP_SEEK("DeepSeek", "DeepSeek"),
    AZURE_OPEN_AI("AzureOpenAI", "Azure OpenAI"),
    CHAT_GLM("ChatGLM", "ChatGLM");

    @JsonValue
    private final String type;

    private final String name;
}
