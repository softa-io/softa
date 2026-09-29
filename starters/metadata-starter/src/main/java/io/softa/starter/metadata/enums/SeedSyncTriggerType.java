package io.softa.starter.metadata.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * What started a seed sync batch.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum SeedSyncTriggerType {
    // The platform admin synced a release's changes.
    MANUAL("Manual"),
    // The platform admin re-ran the tenants a batch failed on.
    MANUAL_RETRY("ManualRetry"),
    // A tenant was set up (or rebuilt) with the tenant files it is due.
    PROVISION("Provision"),
    // A tenant's plan now entitles packages it never had.
    PLAN_CHANGE("PlanChange"),
    // A tenant has a company in a country it had no files for.
    COUNTRY_ADDED("CountryAdded"),
    ;

    @JsonValue
    private final String type;
}
