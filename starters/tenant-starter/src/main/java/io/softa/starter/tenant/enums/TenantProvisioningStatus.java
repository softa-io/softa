package io.softa.starter.tenant.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * Tenant initialization (seed) status — a third axis, orthogonal to {@link TenantStatus} (operational)
 * and {@link TenantLifecycle} (billing). Tracks whether the tenant's post-creation business-data seeding
 * has completed. Does NOT gate login (that keys off {@code TenantStatus == ACTIVE}); it drives display,
 * alerting, and the {@code createAdmin} readiness gate.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum TenantProvisioningStatus {
    INITIALIZING("Initializing"),
    READY("Ready"),
    FAILED("Failed"),
    ;

    @JsonValue
    private final String status;

}
