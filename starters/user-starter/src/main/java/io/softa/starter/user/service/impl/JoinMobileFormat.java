package io.softa.starter.user.service.impl;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.PhoneUtils;
import io.softa.starter.referencedata.entity.CountryRegion;
import io.softa.starter.referencedata.service.CountryRegionService;

/**
 * How the join page shows a mobile: split into its country dial code and the masked national
 * number, with the country's name (the join page shows "Singapore +65 912****67").
 * <p>
 * The number is split by {@link PhoneUtils} — its calling code and national number as libphonenumber
 * reads them, so a NANP territory is "+1" and its area code part of the national number
 * ("+1 787****78") — and the country's name comes from the country table.
 */
@Component
@RequiredArgsConstructor
public class JoinMobileFormat {

    private final CountryRegionService countryRegionService;

    /**
     * @param dialCode    "+65", or null when the number does not parse
     * @param countryName the number's country, or null
     * @param masked      "+65 912****67"; a number that does not parse is masked whole
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
        return PhoneUtils.split(mobile)
                .map(parts -> new Formatted("+" + parts.countryCode(), this.countryName(parts.region()),
                        "+" + parts.countryCode() + " " + maskNational(parts.nationalNumber())))
                .orElseGet(() -> new Formatted(null, null, ContactMasking.mobile(mobile)));
    }

    /**
     * The whole number, readable: "+86 13312345678" — the dial code, a space, the national number.
     * For where the person is told their own numbers in full, never for logs or lists (those take
     * {@link #mask}). A number that does not parse comes back as stored.
     */
    public String spaced(String mobile) {
        if (StringUtils.isBlank(mobile)) {
            return null;
        }
        return PhoneUtils.split(mobile)
                .map(parts -> "+" + parts.countryCode() + " " + parts.nationalNumber())
                .orElse(mobile);
    }

    /** The name of the number's country, or null when the number's calling code is shared and unplaced. */
    private String countryName(String region) {
        if (region == null) {
            return null;
        }
        try {
            return countryRegionService.findByCode(region).map(CountryRegion::getName).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** First three and last two digits shown: 91234567 → 912****67. */
    static String maskNational(String national) {
        if (national.length() <= 5) {
            return "****" + national.substring(Math.max(0, national.length() - 2));
        }
        return national.substring(0, 3) + "****" + national.substring(national.length() - 2);
    }
}
