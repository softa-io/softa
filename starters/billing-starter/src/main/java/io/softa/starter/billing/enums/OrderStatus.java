package io.softa.starter.billing.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * PendingPayment, InProgress, Completed, Cancelled, Refunded
 */
@Getter
@AllArgsConstructor
public enum OrderStatus {
    PENDING_PAYMENT("PendingPayment"),
    IN_PROGRESS("InProgress"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled"),
    REFUNDED("Refunded"),
    ;

    @JsonValue
    private final String status;
}
