package io.softa.starter.message.sms.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Status of an outgoing SMS record.
 */
@Getter
@AllArgsConstructor
public enum SmsSendStatus {
    PENDING("Pending", "Queued, not yet sent"),
    SENT("Sent", "Successfully delivered to the SMS provider"),
    FAILED("Failed", "Delivery failed, no further retry"),
    RETRY("Retry", "Delivery failed, scheduled for retry");

    @JsonValue
    private final String code;
    private final String description;
}
