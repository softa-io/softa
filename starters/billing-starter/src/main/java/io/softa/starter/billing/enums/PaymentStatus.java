package io.softa.starter.billing.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Unpaid, Paid, Failed, Canceled, Refunded
 */
@Getter
@AllArgsConstructor
public enum PaymentStatus {
    UNPAID("Unpaid"),
    PAID("Paid"),
    FAILED("Failed"),
    CANCELED("Canceled"),
    REFUNDED("Refunded"),
    ;

    @JsonValue
    private final String status;
}
