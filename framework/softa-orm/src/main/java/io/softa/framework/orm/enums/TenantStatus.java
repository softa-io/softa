package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Tenant status enum.
 */
@Getter
@AllArgsConstructor
public enum TenantStatus {
    DRAFT("Draft", "Draft"),
    ACTIVE("Active", "Active"),
    SUSPENDED("Suspended", "Suspended"),
    CLOSED("Closed", "Closed"),
    ;

    @JsonValue
    private final String status;
    private final String name;

}
