package io.softa.starter.cron.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Tenant execution mode for cross-tenant scenarios (e.g., scheduled tasks, system jobs).
 */
@Getter
@AllArgsConstructor
public enum TenantJobMode {
    /** Execute once per active tenant, each with its own tenant context */
    PER_TENANT("PerTenant"),
    /** Skip tenant isolation, operate across all tenants */
    CROSS_TENANT("CrossTenant");

    @JsonValue
    private final String mode;
}
