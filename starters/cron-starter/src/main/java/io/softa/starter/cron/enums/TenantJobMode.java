package io.softa.starter.cron.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionItem;
import io.softa.framework.orm.annotation.OptionSet;

/**
 * Tenant execution mode for cross-tenant scenarios (e.g., scheduled tasks, system jobs).
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Tenant Job Mode")
public enum TenantJobMode {
    @OptionItem(description = "Execute once per active tenant, each with its own tenant context")
    PER_TENANT("PerTenant"),
    @OptionItem(description = "Skip tenant isolation, operate across all tenants")
    CROSS_TENANT("CrossTenant");

    @JsonValue
    private final String mode;
}
