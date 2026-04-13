package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Tenant lifecycle stage enum.
 */
@Getter
@AllArgsConstructor
public enum TenantLifecycle {
    TRIAL("Trial", "Trial"),
    SUBSCRIBED("Subscribed", "Subscribed"),
    GRACE_PERIOD("GracePeriod", "Grace Period"),
    OFFBOARDING("Offboarding", "Offboarding"),
    ARCHIVED("Archived", "Archived"),
    ;

    @JsonValue
    private final String stage;
    private final String name;

}
