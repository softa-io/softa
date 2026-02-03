package io.softa.starter.ai.dto;

import com.plexpt.chatgpt.entity.billing.Usage;
import lombok.Data;

/**
 * AI response content, with content and token usage
 */
@Data
public class AiContent {

    private StringBuffer content = new StringBuffer();

    private Usage usage;

    private boolean stream;

    public AiContent(Boolean stream) {
        this.stream = stream;
    }

    public void append(String content) {
        this.content.append(content);
    }
}
