package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Account Status
 */
@Getter
@AllArgsConstructor
public enum AccountStatus {
    ACTIVE("Active", "Active"),
    UNVERIFIED("Unverified", "Unverified"),
    FROZEN("Frozen", "Frozen"),
    PENDING_DELETION("PendingDeletion", "Pending Deletion"),
    DELETED("Deleted", "Deleted"),
    BLACKLISTED("Blacklisted", "Blacklisted")
    ;
    @JsonValue
    private final String status;

    private final String description;
}
