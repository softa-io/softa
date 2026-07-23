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
import io.softa.starter.tenant.enums.TenantProvisioningStatus;
import io.softa.starter.tenant.enums.TenantStatus;

/**
 * TenantInfo Model — the platform tenant registry. Lives in tenant-starter so it can
 * reference the reference-data master tables by code. The framework only
 * depends on the {@code TenantInfoService} SPI (active ids / isTenantActive / deactivate),
 * never on this entity.
 *
 * <p>It owns the optional 1:1 link to the tenant's version via {@link #subscriptionId}
 * (owner-side FK to {@link TenantSubscription}). The link is nullable — apps that don't sell
 * versions leave it unset and the entitlement resolver defaults to Free.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        softDelete = true
)
@Index(indexName = "uk_tenant_info_subscription", fields = {"subscriptionId"}, unique = true)
public class TenantInfo extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field
    private String name;

    @Field
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

    @Field
    private Timezone defaultTimezone;

    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = Currency.class,
            description = "Default billing/display currency — FK to currency.id (ISO 4217 alpha-3, "
                    + "code-as-id). Seed default for new invoices/orders.")
    private String defaultCurrency;

    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = CountryRegion.class,
            description = "Default country/region — FK to country_region.id (ISO 3166-1 alpha-2, "
                    + "code-as-id). Seed default for new users, billing addresses, locale hints.")
    private String defaultCountry;

    @Field(description = "Data-residency region this tenant's data is hosted in (platform-fixed set)")
    private DataRegion dataRegion;

    @Field(fieldType = FieldType.ONE_TO_ONE, relatedModel = TenantSubscription.class,
            description = "The tenant's version/subscription (1:1; owner-side FK). Nullable — apps "
                    + "that don't sell versions leave it unset and the resolver defaults to Free.")
    private Long subscriptionId;

    @Field(label = "Provisioning Status",
            description = "Post-creation seed progress (INITIALIZING -> READY / FAILED). Orthogonal to "
                    + "status/lifecycle; does not gate login — drives display, alerting, the createAdmin gate.")
    private TenantProvisioningStatus provisioningStatus;

    @Field
    private Boolean deleted;
}
