package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Status of an outgoing mail record.
 */
@Getter
@AllArgsConstructor
public enum MailSendStatus {
    PENDING("Pending", "Queued, not yet sent"),
    SENT("Sent", "Successfully delivered to the SMTP server"),
    FAILED("Failed", "Delivery failed, no further retry"),
    RETRY("Retry", "Delivery failed, scheduled for retry");

    @JsonValue
    private final String code;
    private final String description;
}
