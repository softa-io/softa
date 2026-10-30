package io.softa.starter.seed.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * What a {@link io.softa.starter.seed.entity.SeedLoadBatch} did.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum SeedLoadType {
    /** The platform files, loaded by the platform admin. */
    PLATFORM("Platform"),
    /** The tenant files, run in the tenants. */
    TENANT("Tenant"),
    /** The tenant files a new tenant is set up with. */
    PROVISION("Provision"),
    /** Named tenant files run again in named tenants, loading whole over the tenants' copies. */
    REAPPLY("Reapply"),
    /** The one-time recording of the tenant files already present in an existing environment. */
    INITIALIZE("Initialize"),
    ;

    @JsonValue
    private final String code;
}
