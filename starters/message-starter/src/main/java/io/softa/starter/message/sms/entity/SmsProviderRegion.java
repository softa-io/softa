package io.softa.starter.message.sms.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.referencedata.entity.CountryRegion;

/**
 * Per-country SMS provider routing row.
 * <p>
 * Each row asserts "{@code providerConfigId} serves recipients in
 * {@code regionCode} with {@code priority}". These rows are the only statement
 * of which countries SMS can be sent to: a provider serves a region only if an
 * enabled row exists for that {@code (provider, region)} pair, and a region
 * with no enabled row pointing to an enabled provider config is not supported
 * — sending to it fails. There is no catchall provider and no {@code "*"}
 * magic value; {@code region_code} is always a real ISO 3166-1 alpha-2 code.
 * <p>
 * {@code SmsProviderDispatcher} orders a region's candidates by
 * {@code priority} asc, then config id; the first one is used.
 * To make a global provider eligible for TW traffic, explicitly add a TW
 * row for that provider. Explicit configuration prevents routing to the wrong
 * provider (e.g. accidentally sending TW traffic through a mainland-CN line).
 *
 * <p><b>Platform-owned</b>: routing exists once, for every tenant — SMS is a
 * platform service. The table is not multi-tenant and has no tenant column.
 */
@Data
@Model(
        label = "SMS Provider Region",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        activeControl = true,
        defaultOrder = {"regionCode", "priority"}
)
@Index(indexName = "idx_region_active", fields = {"regionCode", "active"})
@Index(indexName = "uk_sms_provider_region", fields = {"providerConfigId", "regionCode"}, unique = true,
        message = "This provider already has a route for this country; edit or re-enable that route instead.")
@EqualsAndHashCode(callSuper = true)
public class SmsProviderRegion extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    // onDelete = CASCADE: routes are owned by the provider — deleting the provider removes its routes.
    @Field(label = "Provider", required = true, fieldType = FieldType.MANY_TO_ONE,
            relatedModel = SmsProviderConfig.class, onDelete = OnDelete.CASCADE,
            description = "The provider this route sends through")
    private Long providerConfigId;

    @Field(required = true, fieldType = FieldType.MANY_TO_ONE, relatedModel = CountryRegion.class,
            description = "Routed country — FK to country_region.id (ISO 3166-1 alpha-2, code-as-id). "
                    + "Mainland China (CN), Taiwan (TW), Hong Kong (HK), Macau (MO) are four "
                    + "distinct codes — configure each explicitly if needed on different providers. "
                    + "No magic values like '*': a country without a row is not supported.")
    private String regionCode;

    /** Denormalised so list views can show "CN (+86)" without joining country_region per query. */
    @Field(cascadedField = "regionCode.dialCode",
            description = "ITU-T E.164 dial code (digits only, no leading +). Stored cascade "
                    + "derived from country_region.dial_code via the regionCode relation — "
                    + "framework-maintained, readonly.")
    private String dialCode;

    @Field(required = true,
            description = "Lower = higher priority. Ordered ascending within the same "
                    + "region for provider selection; ties broken by config id. "
                    + "Defaults to 100 so new rows sort after any explicitly-prioritised ones.")
    private Integer priority;

    /**
     * Framework active control: reads are auto-filtered to {@code active = true}, so a
     * disabled route drops out of dispatch without deleting the row — the intended
     * mechanism for temporary fault isolation. Filter on {@code active} explicitly to
     * see disabled routes.
     */
    @Field(required = true, renamedFrom = "isEnabled")
    private Boolean active;
}
