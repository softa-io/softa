package io.softa.starter.referencedata.enums;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.annotation.OptionItem;
import io.softa.framework.orm.annotation.OptionSet;

/**
 * Continent enumeration — 7-continent model (the most widely-used scheme
 * across education, UN regional groupings, and IAB taxonomies).
 *
 * <p>Codes are 2-letter to keep persistence compact and unambiguous (the
 * 7-continent set has no ISO standard so the codes are project conventions
 * rather than ISO 3166 / UN M49 codes). Use these as enum constants in code
 * and persist them as the {@link #code} string.
 *
 * <p>Lives in {@code reference-data-starter} alongside {@code CountryRegion}
 * and {@code Currency} because the only consumer of {@code Continent} is
 * {@code CountryRegion.continent}; there is no realistic "I need continents
 * but not countries" scenario. 7 stable values, no rich data — enum is the
 * right shape (in contrast to country/currency which warrant entities).
 *
 * <p>{@code @OptionSet} declares this enum as a metadata-managed option set;
 * itemCode is derived from the {@code @JsonValue}-annotated {@link #code}
 * field, matching the persisted string in {@code country_region.continent}.
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Continent", description = "7-continent model")
public enum Continent {
    @OptionItem(label = "Asia")
    AS("AS"),

    @OptionItem(label = "Europe")
    EU("EU"),

    @OptionItem(label = "Africa")
    AF("AF"),

    @OptionItem(label = "North America")
    NA("NA"),

    @OptionItem(label = "South America")
    SA("SA"),

    @OptionItem(label = "Oceania")
    OC("OC"),

    @OptionItem(label = "Antarctica")
    AN("AN"),
    ;

    @JsonValue
    private final String code;

    private static final Map<String, Continent> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(Continent::getCode, Function.identity()));

    /**
     * Resolve a {@link Continent} from its code. Returns {@code null} when
     * the input is null or doesn't match any known continent.
     */
    public static Continent of(String code) {
        if (code == null) return null;
        return CODE_MAP.get(code);
    }
}
