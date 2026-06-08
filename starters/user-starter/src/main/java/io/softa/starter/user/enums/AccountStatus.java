package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionSet;

/**
 * Account Status
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Account Status")
public enum AccountStatus {
    ACTIVE("Active"),
    UNVERIFIED("Unverified"),
    LOCKED("Locked"),
    FROZEN("Frozen"),
    PENDING_DELETION("PendingDeletion"),
    DELETED("Deleted"),
    BLACKLISTED("Blacklisted"),
    ;

    @JsonValue
    private final String status;
}
