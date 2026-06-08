package io.softa.starter.tenant.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionSet;

/**
 * PendingPayment, InProgress, Completed, Cancelled, Refunded
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Order Status")
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
