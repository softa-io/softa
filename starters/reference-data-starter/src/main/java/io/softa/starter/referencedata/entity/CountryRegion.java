package io.softa.starter.referencedata.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.referencedata.enums.Continent;

/**
 * Platform-level country/region master keyed by ISO 3166-1 alpha-2 code.
 * Read-only reference data — same rows serve all tenants. Seed loaded from
 * {@code data-system/CountryRegion.AllCountries.json} via metadata-starter's
 * {@code POST /SysPreData/loadPreSystemData}.
 *
 * <p>Natural key is {@link #code} (ISO 3166-1 alpha-2). Other tables that
 * reference a country (e.g. {@code SmsProviderRegion.regionCode},
 * {@code TenantInfo.defaultCountry}) store the alpha-2 string as a concept
 * FK — no relational FK constraint, just a documented convention validated
 * at the service layer.
 *
 * <p>The {@link #currencyCode} field is a string FK to {@code currency.code}
 * by the same convention. Mainland China (CN), Taiwan (TW), Hong Kong (HK),
 * Macau (MO) are distinct entries.
 */
@Data
@Schema(name = "CountryRegion")
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "Country / Region",
        tableName = "country_region",
        businessKey = {"code"},
        description = "ISO 3166-1 alpha-2 country/region master"
)
@Index(indexName = "uk_code", fields = {"code"}, unique = true)
@Index(indexName = "idx_continent", fields = {"continent"})
@Index(indexName = "idx_currency_code", fields = {"currencyCode"})
@Index(indexName = "idx_eea", fields = {"eea"})
public class CountryRegion extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Field(label = "ISO 3166-1 alpha-2", required = true, length = 2,
            description = "ISO 3166-1 alpha-2 code (CN/US/TW/...); natural key")
    private String code;

    @Field(label = "Name", required = true, length = 100,
            description = "ISO 3166-1 standard English short name")
    private String name;

    @Field(label = "ISO 3166-1 alpha-3", required = true, length = 3,
            description = "ISO 3166-1 alpha-3 (CHN/USA/TWN); 3-letter code for SWIFT / Stripe")
    private String alpha3Code;

    @Field(label = "Dial Code", required = true, length = 8,
            description = "ITU-T E.164 country dial code, digits only (no leading +)")
    private String dialCode;

    @Field(label = "Currency Code", required = true, length = 3,
            description = "Default ISO 4217 currency alpha-3 code; concept FK to currency.code")
    private String currencyCode;

    @Field(label = "Continent", required = true,
            description = "Continent (7-continent model)")
    private Continent continent;

    @Field(label = "EEA / EU Member",
            description = "EEA / EU member flag — GDPR scope, VAT reverse charge eligibility")
    private Boolean eea;

    @Field(label = "Has Subdivisions",
            description = "True if country_subdivision rows exist for this country")
    private Boolean hasSubdivisions;
}
