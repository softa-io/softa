package io.softa.starter.message.sms.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Status of an outgoing SMS record.
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "SMS Send Status")
public enum SmsSendStatus {
    @OptionItem(description = "Queued, not yet sent")
    PENDING("Pending"),
    @OptionItem(description = "In-flight: picked up by a consumer, provider call in progress")
    SENDING("Sending"),
    @OptionItem(description = "Successfully delivered to the SMS provider")
    SENT("Sent"),
    @OptionItem(description = "Delivery failed, no further retry")
    FAILED("Failed"),
    @OptionItem(description = "Delivery failed, scheduled for retry")
    RETRY("Retry"),
    @OptionItem(description = "Exceeded retry budget, moved to DLQ for manual intervention")
    DEAD_LETTER("DeadLetter");

    @JsonValue
    private final String code;
}
