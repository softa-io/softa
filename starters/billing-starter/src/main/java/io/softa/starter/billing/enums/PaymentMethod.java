package io.softa.starter.billing.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * WeChat, AliPay, Stripe, PayPal, ApplePay, OfflinePayment,
 */
@Getter
@AllArgsConstructor
public enum PaymentMethod {
    WE_CHAT("WeChat"),
    ALI_PAY("AliPay"),
    STRIPE("Stripe"),
    PAY_PAL("PayPal"),
    APPLE_PAY("ApplePay"),
    OFFLINE_PAYMENT("OfflinePayment"),
    ;

    @JsonValue
    private final String type;
}
