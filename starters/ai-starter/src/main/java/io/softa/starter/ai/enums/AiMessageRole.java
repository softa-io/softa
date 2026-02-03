package io.softa.starter.ai.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI Message Role Enum
 */
@Getter
@AllArgsConstructor
public enum AiMessageRole {
    USER("User"),
    ASSISTANT("Assistant"),
    SYSTEM("System"),
    TOOL("Tool"),
    FUNCTION("Function");

    @JsonValue
    private final String type;

}