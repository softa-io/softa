package io.softa.starter.ai.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionSet;

/**
 * AI Message Role Enum
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "AI Message Role")
public enum AiMessageRole {
    USER("User"),
    ASSISTANT("Assistant"),
    SYSTEM("System"),
    TOOL("Tool"),
    FUNCTION("Function");

    @JsonValue
    private final String type;

}
