package io.softa.starter.message.shared;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;

/**
 * What a message is for, in the terms carriers and regulators sort traffic by. A provider that routes
 * or prices by it is told in its own words by its adapter (Aliyun Global: {@code OTP} / {@code NOTIFY}
 * / {@code MKT}); the others ignore it.
 * <p>
 * SMS only for now. It sits here, not in the SMS package, so mail can take the same values when it
 * has a use for them.
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Message Category")
public enum MessageCategory {
    @OptionItem(label = "OTP", description = "One-time passcode or verification code")
    OTP("OTP"),
    @OptionItem(description = "Sent because of the recipient's own business: approvals, payslips, account changes")
    TRANSACTIONAL("Transactional"),
    @OptionItem(description = "Promotional content the recipient did not ask for")
    MARKETING("Marketing");

    @JsonValue
    private final String code;
}
