package io.softa.starter.message.sms.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Delivery status reported by the SMS provider.
 */
@Getter
@AllArgsConstructor
public enum SmsDeliveryStatus {
    UNKNOWN("Unknown", "Delivery status not yet available"),
    DELIVERED("Delivered", "Message delivered to the recipient"),
    UNDELIVERED("Undelivered", "Message could not be delivered"),
    FAILED("Failed", "Message delivery failed permanently");

    @JsonValue
    private final String code;
    private final String description;
}
