package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Persistent approval task status for flow runtime projections.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum FlowApprovalTaskStatus {
    PENDING("Pending"),
    APPROVED("Approved"),
    REJECTED("Rejected"),
    RETURNED("Returned"),
    TRANSFERRED("Transferred"),
    DELEGATED("Delegated"),
    CANCELLED("Cancelled"),
    WITHDRAWN("Withdrawn"),
    /** A CC task its recipient has read; an unread one is {@link #PENDING}. */
    READ("Read"),
    ;

    @JsonValue
    private final String type;
}
