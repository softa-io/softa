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
 * The IANA time zones of each country — what a company's Time Zone is picked from once its country
 * is chosen.
 *
 * <p>Seed data, generated from the tz database's {@code zone.tab} (one line per zone, each tagged
 * with the country it is in) into {@code CountryTimeZone.Default.json}. Regenerate the file when the
 * tz database adds or retires a zone; nothing here is edited by hand at runtime.
 *
 * <p><b>The id is the IANA zone id itself</b> ({@code Asia/Singapore}), so the field that points here
 * stores exactly the string it stored as free text before, and every reader that hands it to
 * {@code ZoneId.of} keeps working unchanged.
 *
 * <p><b>One default per country.</b> {@code zone.tab} lists a country's zones in geographical order,
 * so its first zone is often not where most of the country keeps time — Australia starts at Lord
 * Howe Island, Canada at Newfoundland. The seed takes the first zone except for those countries,
 * where it names the capital's or the most populous zone instead, and gives the default sequence 0 so
 * a list ordered by sequence starts with it.
 *
 * <p>Not {@code multiCountry}: that narrows every read to the country of the company the user is
 * working in, while a company form needs the zones of the country being chosen on it. Callers filter
 * by {@code country} themselves.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "Time Zone",
        idStrategy = IdStrategy.EXTERNAL_ID,
        displayName = {"name"},
        searchName = {"id", "name"},
        defaultOrder = {"sequence"},
        description = "IANA time zones of each country, from the tz database's zone.tab.")
@Index(indexName = "idx_country_time_zone_country", fields = {"country", "sequence"})
public class CountryTimeZone extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The IANA zone id, e.g. {@code Asia/Singapore}; the longest in {@code zone.tab} is 30 characters. */
    @Field(label = "ID", length = 32)
    private String id;

    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = CountryRegion.class, required = true)
    private String country;

    /** The zone id, plus the area it covers when the country has more than one, e.g. {@code Pacific/Chatham — Chatham Islands}. */
    @Field(required = true, length = 128)
    private String name;

    /** Order within the country: the default zone is 0, the rest follow {@code zone.tab}. */
    @Field(defaultValue = "0", min = "0", constraintMessage = "Display order cannot be negative.")
    private Integer sequence;

    /** The zone a company of this country gets when none is chosen. Exactly one per country. */
    @Field(label = "Default Zone", defaultValue = "false")
    private Boolean defaultZone;
}
