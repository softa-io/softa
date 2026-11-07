package io.softa.starter.referencedata.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.referencedata.enums.Continent;

/**
 * Platform-level country/region master keyed by ISO 3166-1 alpha-2 code.
 * Read-only reference data — same rows serve all tenants. Seed loaded from
 * {@code data-system/CountryRegion.AllCountries.json} by seed-starter's
 * platform load ({@code POST /SeedData/platform/load}).
 *
 * <p>Code-as-id: the primary key {@link #id} <b>is</b> the ISO 3166-1 alpha-2 code
 * (EXTERNAL_ID). Tables reference a country by storing this code in an id-FK
 * ({@code TenantInfo.defaultCountry}, {@code SmsProviderRegion.regionCode}). Mainland China (CN),
 * Taiwan (TW), Hong Kong (HK), Macau (MO) are distinct entries.
 *
 * <p>The {@link #currencyCode} field is an FK to {@code currency.id} (ISO 4217 alpha-3, code-as-id).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "Country / Region",
        idStrategy = IdStrategy.EXTERNAL_ID,
        businessKey = {"id"},
        description = "ISO 3166-1 alpha-2 country/region master",
        searchName = {"id", "alpha3Code", "name"},
        defaultOrder = {"name"}
)
@Index(indexName = "idx_continent", fields = {"continent"})
@Index(indexName = "idx_currency_code", fields = {"currencyCode"})
@Index(indexName = "idx_eea", fields = {"eea"})
public class CountryRegion extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID", length = 2,
            description = "ISO 3166-1 alpha-2 code (CN/US/TW/...); primary key = the code")
    private String id;

    @Field(required = true, length = 100,
            description = "ISO 3166-1 standard English short name")
    private String name;

    @Field(label = "ISO 3166-1 alpha-3", required = true, length = 3,
            description = "ISO 3166-1 alpha-3 (CHN/USA/TWN); 3-letter code for SWIFT / Stripe")
    private String alpha3Code;

    @Field(required = true, length = 8,
            description = "ITU-T E.164 country dial code, digits only (no leading +)")
    private String dialCode;

    @Field(required = true, fieldType = FieldType.MANY_TO_ONE, relatedModel = Currency.class,
            description = "Default currency — FK to currency.id (ISO 4217 alpha-3, code-as-id)")
    private String currencyCode;

    @Field(required = true,
            description = "Continent (7-continent model)")
    private Continent continent;

    @Field(label = "EEA / EU Member",
            description = "EEA / EU member flag — GDPR scope, VAT reverse charge eligibility")
    private Boolean eea;

    @Field(description = "True if country_subdivision rows exist for this country")
    private Boolean hasSubdivisions;
}
