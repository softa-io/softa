package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.orm.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Tenant lifecycle stage enum.
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Tenant Lifecycle")
public enum TenantLifecycle {
    TRIAL("Trial"),
    SUBSCRIBED("Subscribed"),
    GRACE_PERIOD("GracePeriod"),
    OFFBOARDING("Offboarding"),
    ARCHIVED("Archived"),
    ;

    @JsonValue
    private final String stage;

}
