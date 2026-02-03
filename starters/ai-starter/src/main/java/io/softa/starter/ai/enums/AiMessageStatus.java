package io.softa.starter.ai.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI Message Status Enum
 */
@Getter
@AllArgsConstructor
public enum AiMessageStatus {

    PENDING("Pending"),
	INTERRUPTED("Interrupted"),
	COMPLETED("Completed"),
	FAILED("Failed");

    @JsonValue
    private final String type;

}