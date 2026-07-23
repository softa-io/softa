package io.softa.starter.tenant.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * Per-seeder progress recorded in {@link io.softa.starter.tenant.entity.TenantSeedProgress}: a seeder is
 * either DONE for a tenant or FAILED terminally. Absence of a row = not started / in flight.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum SeederStatus {
    DONE("Done"),
    // Currently unwritten by design: seeders report only success (a failure is retried via redelivery), and
    // the tenant-level FAILED comes from TenantProvisioningStatusService.failTimedOut() (the timeout guard).
    // Retained as the symmetric hook for a future DLQ handler that would publish success=false — see
    // TenantProvisioningStatusService.markSeederFailed.
    FAILED("Failed"),
    ;

    @JsonValue
    private final String status;

}
