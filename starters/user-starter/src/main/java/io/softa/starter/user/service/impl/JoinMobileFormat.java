package io.softa.starter.user.service.impl;

import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.softa.starter.referencedata.entity.CountryRegion;
import io.softa.starter.referencedata.service.CountryRegionService;

/**
 * How the join page shows a mobile: split into its country dial code and the masked national
 * number, with the country's name (the join page shows "Singapore +65 912****67").
 */
@Component
@RequiredArgsConstructor
public class JoinMobileFormat {

    private final CountryRegionService countryRegionService;

    /**
     * @param dialCode    "+65", or null when no country's dial code prefixes the number
     * @param countryName the country of that dial code, or null
     * @param masked      "+65 912****67"; a number with no recognised dial code is masked whole
     */
    public record Formatted(String dialCode, String countryName, String masked) {
    }

    /**
     * The one masked form of a mobile, wherever it is shown or recorded: "+65 912****67" — the dial
     * code, a space, the first three national digits, four stars, the last two. Null for none.
     */
    public String mask(String mobile) {
        return this.format(mobile).masked();
    }

    public Formatted format(String mobile) {
        if (StringUtils.isBlank(mobile)) {
            return new Formatted(null, null, null);
        }
        String digits = mobile.replaceAll("[^0-9]", "");
        CountryRegion country = this.countryOf(digits);
        if (country == null) {
            return new Formatted(null, null, ContactMasking.mobile(mobile));
        }
        String national = digits.substring(country.getDialCode().length());
        return new Formatted("+" + country.getDialCode(), country.getName(),
                "+" + country.getDialCode() + " " + maskNational(national));
    }

    /**
     * The number after its country's dial code — "91234567" for "+6591234567" — or null when no
     * country's dial code prefixes it.
     */
    public String nationalNumber(String mobile) {
        if (StringUtils.isBlank(mobile)) {
            return null;
        }
        String digits = mobile.replaceAll("[^0-9]", "");
        CountryRegion country = this.countryOf(digits);
        return country == null ? null : digits.substring(country.getDialCode().length());
    }

    /** The country whose dial code is the longest prefix of the digits. */
    private CountryRegion countryOf(String digits) {
        List<CountryRegion> countries;
        try {
            countries = countryRegionService.searchList();
        } catch (RuntimeException e) {
            return null;
        }
        return countries.stream()
                .filter(c -> StringUtils.isNotBlank(c.getDialCode()) && digits.startsWith(c.getDialCode())
                        && digits.length() > c.getDialCode().length())
                // Longest first; among countries sharing one code (+1, +7) the first by code.
                .min(Comparator.comparingInt((CountryRegion c) -> -c.getDialCode().length())
                        .thenComparing(CountryRegion::getId))
                .orElse(null);
    }

    /** First three and last two digits shown: 91234567 → 912****67. */
    static String maskNational(String national) {
        if (national.length() <= 5) {
            return "****" + national.substring(Math.max(0, national.length() - 2));
        }
        return national.substring(0, 3) + "****" + national.substring(national.length() - 2);
    }
}
