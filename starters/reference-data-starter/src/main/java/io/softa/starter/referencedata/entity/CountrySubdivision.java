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

/**
 * ISO 3166-2 country subdivisions (provinces, states, prefectures, etc.).
 * Platform-level reference data.
 *
 * <p>Code-as-id: the primary key {@link #id} <b>is</b> the ISO 3166-2 full code
 * (EXTERNAL_ID). {@link #countryCode} is an FK to {@code country_region.id} (ISO alpha-2).
 *
 * <p>Table and entity are created but <b>data is not seeded</b> — populated when
 * address/tax/shipping features land. {@code CountryRegion} exposes a {@code hasSubdivisions}
 * boolean as the runtime indicator of whether this table has data for a given country.
 *
 * <p>Hierarchical subdivisions (e.g. Chinese {@code 省→市} or Japanese {@code 都道府県→市})
 * use {@link #parentCode} as an FK to this table's own {@code id} (a self-relation).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.EXTERNAL_ID,
        businessKey = {"id"},
        description = "ISO 3166-2 country subdivisions",
        searchName = {"id", "name"},
        defaultOrder = {"countryCode", "name"}
)
@Index(indexName = "idx_country", fields = {"countryCode"})
@Index(indexName = "idx_parent", fields = {"parentCode"})
public class CountrySubdivision extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID", length = 10,
            description = "ISO 3166-2 full code (CN-31 / US-CA / JP-13); primary key = the code")
    private String id;

    @Field(required = true, fieldType = FieldType.MANY_TO_ONE, relatedModel = CountryRegion.class,
            description = "Owning country — FK to country_region.id (ISO 3166-1 alpha-2, code-as-id)")
    private String countryCode;

    @Field(required = true, length = 100,
            description = "English name")
    private String name;

    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = CountrySubdivision.class,
            description = "Parent subdivision for hierarchical regions — FK to country_subdivision.id "
                    + "(self-relation); null for top-level")
    private String parentCode;

    @Field(length = 20,
            description = "Subdivision type — province / state / prefecture / region / municipality / county")
    private String type;
}
