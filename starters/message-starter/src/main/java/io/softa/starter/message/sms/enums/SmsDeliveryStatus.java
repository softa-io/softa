package io.softa.starter.message.sms.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Delivery status reported by the SMS provider.
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "SMS Delivery Status")
public enum SmsDeliveryStatus {
    @OptionItem(description = "Delivery status not yet available")
    UNKNOWN("Unknown"),
    @OptionItem(description = "Message delivered to the recipient")
    DELIVERED("Delivered"),
    @OptionItem(description = "Message could not be delivered")
    UNDELIVERED("Undelivered"),
    @OptionItem(description = "Message delivery failed permanently")
    FAILED("Failed");

    @JsonValue
    private final String code;
}
