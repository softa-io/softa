package io.softa.starter.tenant.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.enums.Language;
import io.softa.framework.base.enums.Timezone;
import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.referencedata.entity.CountryRegion;
import io.softa.starter.referencedata.entity.Currency;
import io.softa.starter.tenant.enums.DataRegion;
import io.softa.starter.tenant.enums.TenantStatus;

/**
 * TenantInfo Model — the platform tenant registry. Lives in tenant-starter so it can
 * reference the reference-data master tables by code. The framework only
 * depends on the {@code TenantInfoService} SPI (active ids / isTenantActive / deactivate),
 * never on this entity.
 *
 * <p>It owns the optional 1:1 link to the tenant's version via {@link #subscriptionId}
 * (owner-side FK to {@link TenantSubscription}). The link is nullable — apps that don't sell
 * versions leave it unset and the entitlement resolver falls back to the floor plan (the catalog's
 * lowest tier, whatever the deployment named it; empty when there is no catalog).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        softDelete = true
)
@Index(indexName = "uk_tenant_info_subscription", fields = {"subscriptionId"}, unique = true)
@Index(indexName = "uk_tenant_info_code", fields = {"code"}, unique = true,
        message = "A tenant with this code already exists.")
public class TenantInfo extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    // Required: both are natural keys people reach for when locating a tenant. Leaving code
    // optional also let a blank form submit an empty string, and '' — unlike NULL — occupies
    // uk_tenant_info_code, so the FIRST blank-code tenant silently reserved the slot and every
    // later one failed with "A tenant with this code already exists" pointing at a row nobody
    // could see. Making them required removes the empty-string path entirely.
    @Field(required = true)
    private String name;

    @Field(required = true)
    private String code;

    @Field
    private TenantStatus status;

    @Field(copyable = false)
    private LocalDateTime activatedTime;

    @Field(copyable = false)
    private LocalDateTime suspendedTime;

    @Field(copyable = false)
    private LocalDateTime closedTime;

    @Field
    private Language defaultLanguage;

    // Required: every date the tenant lifecycle turns on — subscription activation / expiry, the expiry
    // reminder's send hour, an employee's hire date — is evaluated in THIS zone. Left unset the code falls
    // back to UTC (Timezone.zoneIdOrUtc), which silently shifts those boundaries by up to a day for a
    // tenant that is not actually on UTC. Making it mandatory removes the silent-wrong-answer path.
    @Field(required = true)
    private Timezone defaultTimezone;

    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = Currency.class,
            description = "Default billing/display currency — FK to currency.id (ISO 4217 alpha-3, "
                    + "code-as-id). Seed default for new invoices/orders.")
    private String defaultCurrency;

    // Required: this is the tenant's operating country, and provisioning seeds its first LegalEntity with
    // it — which in turn selects the country-specific employee / entity field sets. There is no sound
    // default (guessing one puts the whole org tree in the wrong country), so it has to be supplied.
    @Field(required = true, fieldType = FieldType.MANY_TO_ONE, relatedModel = CountryRegion.class,
            description = "Default country/region — FK to country_region.id (ISO 3166-1 alpha-2, "
                    + "code-as-id). Seed default for new users, billing addresses, locale hints.")
    private String defaultCountry;

    @Field(description = "Data-residency region this tenant's data is hosted in (platform-fixed set)")
    private DataRegion dataRegion;

    @Field(fieldType = FieldType.ONE_TO_ONE, relatedModel = TenantSubscription.class,
            description = "The tenant's version/subscription (1:1; owner-side FK). Nullable — apps "
                    + "that don't sell versions leave it unset and the resolver falls back to the "
                    + "floor plan (the catalog's lowest tier).")
    private Long subscriptionId;

    @Field(label = "Last Seed Sync", fieldType = FieldType.MANY_TO_ONE, relatedModelName = "SeedSyncTask",
            copyable = false,
            description = "The tenant's latest seed sync task — its setup or a later sync — pointed at by the "
                    + "seed sync as it hands the task out; its status is the tenant's seed sync status.")
    private Long lastSeedSyncTaskId;

    @Field
    private Boolean deleted;
}
