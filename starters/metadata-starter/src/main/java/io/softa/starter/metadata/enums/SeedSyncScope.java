package io.softa.starter.metadata.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Which seed data a sync batch covers: the platform files every tenant reads, the tenants' own copies, or
 * both — so the sync history says at a glance what each batch was for.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum SeedSyncScope {
    // Platform files loaded (or, when tracing, the shared bindings traced); no tenant reached.
    PLATFORM("Platform"),
    // Tenant files only: new rows handed to tenants, a tenant set up, a retry, a tenant traced.
    TENANTS("Tenants"),
    PLATFORM_AND_TENANTS("PlatformAndTenants"),
    ;

    @JsonValue
    private final String scope;
}
