package io.softa.starter.tenant.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionItem;
import io.softa.framework.orm.annotation.OptionSet;

/**
 * Unpaid, Paid, Failed, Canceled, Refunded
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Payment Status")
public enum PaymentStatus {
    UNPAID("Unpaid"),
    PAID("Paid"),
    @OptionItem(label = "Payment Failed")
    FAILED("Failed"),
    @OptionItem(label = "Payment Canceled")
    CANCELED("Canceled"),
    REFUNDED("Refunded"),
    ;

    @JsonValue
    private final String status;
}
