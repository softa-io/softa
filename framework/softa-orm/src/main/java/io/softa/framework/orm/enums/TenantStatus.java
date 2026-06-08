package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.orm.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Tenant status enum.
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Tenant Status")
public enum TenantStatus {
    DRAFT("Draft"),
    ACTIVE("Active"),
    SUSPENDED("Suspended"),
    CLOSED("Closed"),
    ;

    @JsonValue
    private final String status;

}
