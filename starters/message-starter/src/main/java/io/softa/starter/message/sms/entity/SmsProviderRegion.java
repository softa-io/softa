package io.softa.starter.message.sms.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.referencedata.entity.CountryRegion;

/**
 * Per-country SMS provider routing row.
 * <p>
 * Each row asserts "{@code providerConfigId} serves recipients in
 * {@code regionCode} with {@code priority}". A provider serves a region only
 * if at least one enabled row exists for that {@code (provider, region)}
 * pair — absence is a hard no, not an implicit fallback.
 * <p>
 * <b>Catchall behavior is NOT expressed here.</b> Use
 * {@code SmsProviderConfig.isDefault=true} to mark providers that should be
     * eligible when no precise routing matches. Keeping the two concerns in
 * different fields means {@code region_code} is always a real ISO 3166-1
 * alpha-2 code (no {@code "*"} magic value).
 * <p>
 * Dispatcher resolution order ({@code SmsProviderDispatcher}):
 * <ol>
 *   <li><b>Precise tier</b>: enabled rows whose {@code region_code} equals the
 *       recipient's country, ordered by {@code priority} asc then
 *       {@code SmsProviderConfig.priority} asc.</li>
 *   <li><b>Catchall tier</b>: if precise tier is empty, enabled
 *       {@code SmsProviderConfig} rows with {@code isDefault=true}, ordered
 *       by {@code SmsProviderConfig.priority} asc.</li>
 *   <li>Both empty → {@link io.softa.framework.base.exception.BusinessException}.
 *       Implicit "any enabled provider" fallback is intentionally absent.</li>
 * </ol>
 *
 * <p><b>Tenant scoping</b>: {@code tenant_id = 0} for platform-level routing
 * shared by all tenants; {@code tenant_id > 0} for per-tenant overrides
 * (auto-filled by the ORM tenant filter).
 *
     * <p><b>Precise match wins fully</b> — once the precise tier yields an
     * enabled provider, the dispatcher does NOT merge in the catchall tier.
     * To make a global provider eligible for TW traffic, explicitly add a TW
     * row for that provider too. Explicit
 * configuration over implicit fallback prevents routing to the wrong
 * provider (e.g. accidentally sending TW traffic through a mainland-CN line).
 */
@Data
@Model(
        label = "SMS Provider Region",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        multiTenant = true
)
@Index(indexName = "idx_region_enabled", fields = {"regionCode", "isEnabled"})
@Index(indexName = "uk_tenant_provider_region", fields = {"tenantId", "providerConfigId", "regionCode"}, unique = true)
@EqualsAndHashCode(callSuper = true)
public class SmsProviderRegion extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID",
            description = "0 = platform-level (shared across tenants); >0 = tenant-level. "
                    + "Auto-stamped by the ORM on writes when multi-tenancy is enabled.")
    private Long tenantId;

    @Field(label = "Provider Config ID", required = true, description = "FK → sms_provider_config.id")
    private Long providerConfigId;

    @Field(required = true, fieldType = FieldType.MANY_TO_ONE, relatedModel = CountryRegion.class,
            description = "Routed country — FK to country_region.id (ISO 3166-1 alpha-2, code-as-id). "
                    + "Renders a country picker; portable across environments. Mainland China (CN), "
                    + "Taiwan (TW), Hong Kong (HK), Macau (MO) are four distinct codes — configure "
                    + "each explicitly if you need them on different providers. No magic values like "
                    + "'*'; catchall is via SmsProviderConfig.isDefault.")
    private String regionCode;

    @Field(cascadedField = "regionCode.dialCode",
            description = "ITU-T E.164 dial code (digits only, no leading +). Stored cascade derived "
                    + "from country_region.dial_code via the regionCode relation — framework-maintained, "
                    + "readonly. Lets the admin UI render 'CN (+86) → Aliyun' without joining "
                    + "country_region on every list query.")
    private String dialCode;

    @Field(required = true,
            description = "Lower = higher priority. Ordered ascending within the same "
                    + "region for provider selection; ties broken by SmsProviderConfig.priority asc. "
                    + "Defaults to 100 so new rows sort after any explicitly-prioritised ones.")
    private Integer priority;

    @Field(required = true,
            description = "Row enable switch — false disables this (provider, region) "
                    + "route without deleting the row, useful for temporary fault isolation.")
    private Boolean isEnabled;
}
