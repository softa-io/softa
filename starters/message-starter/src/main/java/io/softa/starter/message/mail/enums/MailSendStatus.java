package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import io.softa.framework.base.enums.OptionItemTone;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Status of an outgoing mail record.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum MailSendStatus {
    @OptionItem(description = "Queued, not yet sent", itemTone = OptionItemTone.NEUTRAL)
    PENDING("Pending"),
    @OptionItem(description = "In-flight: claimed by the delivery loop, send in progress",
            itemTone = OptionItemTone.INFO)
    SENDING("Sending"),
    @OptionItem(description = "Successfully delivered to the SMTP server",
            itemTone = OptionItemTone.SUCCESS)
    SENT("Sent"),
    @OptionItem(description = "Delivery failed, no further retry", itemTone = OptionItemTone.ERROR)
    FAILED("Failed"),
    @OptionItem(description = "Delivery failed, scheduled for retry", itemTone = OptionItemTone.WARNING)
    RETRY("Retry"),
    @OptionItem(description = "Not deliverable — retry budget exhausted or failure is "
            + "non-retryable (auth / config); moved to DLQ for manual intervention",
            itemTone = OptionItemTone.ERROR)
    DEAD_LETTER("DeadLetter");

    @JsonValue
    private final String code;
}
