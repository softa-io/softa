package io.softa.starter.referencedata.entity;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * ISO 3166-2 country subdivisions (provinces, states, prefectures, etc.).
 * Platform-level reference data; concept FK by {@link #countryCode} to
 * {@code country_region.code}.
 *
 * <p>Table and entity are created in this PR but <b>data is not seeded</b>
 * — populated when address/tax/shipping features land. {@code CountryRegion}
 * exposes a {@code hasSubdivisions} boolean as the runtime indicator of
 * whether this table has data for a given country.
 *
 * <p>Hierarchical subdivisions (e.g. Chinese {@code 省→市} or Japanese
 * {@code 都道府県→市}) use {@link #parentCode} to link to the parent
 * subdivision's {@code code}.
 */
@Data
@Schema(name = "CountrySubdivision")
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "Country Subdivision",
        tableName = "country_subdivision",
        businessKey = {"code"},
        description = "ISO 3166-2 country subdivisions"
)
@Index(indexName = "uk_code", fields = {"code"}, unique = true)
@Index(indexName = "idx_country", fields = {"countryCode"})
@Index(indexName = "idx_parent", fields = {"parentCode"})
public class CountrySubdivision extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Field(label = "Country Code", required = true, length = 2,
            description = "ISO 3166-1 alpha-2 country code; concept FK to country_region.code")
    private String countryCode;

    @Field(label = "Code", required = true, length = 10,
            description = "ISO 3166-2 full code (CN-31 / US-CA / JP-13); natural key")
    private String code;

    @Field(label = "Name", required = true, length = 100,
            description = "English name")
    private String name;

    @Field(label = "Parent Code", length = 10,
            description = "Parent subdivision code for hierarchical regions; null for top-level")
    private String parentCode;

    @Field(label = "Type", length = 20,
            description = "Subdivision type — province / state / prefecture / region / municipality / county")
    private String type;
}
