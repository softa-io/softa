package io.softa.framework.base.utils;

import java.util.Optional;
import java.util.regex.Pattern;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.exception.BusinessException;

/**
 * Phone numbers as they are stored and sent: E.164 — {@code +}, the country calling code and the
 * national number, digits only ({@code +6591234567}).
 * <p>
 * Every check here reads the number ranges bundled with libphonenumber, so it is only as current as
 * that library's version. That is why a number is held to its country's <b>length</b>
 * ({@code isPossibleNumber}) and never to its country's <b>assigned ranges</b>
 * ({@code isValidNumber}): a range a country opens after the bundled metadata was built would be
 * refused until the library is upgraded. A number in an unassigned range is left to the SMS
 * provider to refuse.
 * <p>
 * Nothing here guesses a country. A number without its calling code is refused, not read as the
 * caller's or the company's country: the screen that took it is where a country can be picked.
 */
public final class PhoneUtils {

    /**
     * The stored shape, for {@code @Field(pattern = PhoneUtils.E164)}: {@code +}, a non-zero digit,
     * then 6 to 14 more — no spaces or dashes. Shape only; {@link #toE164} also checks the length
     * for the country.
     */
    public static final String E164 = "^\\+[1-9]\\d{6,14}$";

    public static final String E164_MESSAGE =
            "Enter the number with its country code, digits only, e.g. +6591234567.";

    private static final Pattern E164_PATTERN = Pattern.compile(E164);

    /**
     * What a typed number may contain: a plus (also the full-width one), digits and the separators
     * people put between them. Letters are refused rather than read as keypad digits — libphonenumber
     * would turn "+65 9123 4567 abc" into +6591234567222 — and so is an extension.
     */
    private static final Pattern TYPED_NUMBER = Pattern.compile("[+\\uFF0B][0-9\\s().\\-\\u00A0]+");

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    private PhoneUtils() {
    }

    /**
     * A number split into its parts. {@code region} is the ISO 3166-1 alpha-2 country the number
     * belongs to, or null when its calling code is shared (+1, +7, +44, +61) and the bundled ranges
     * cannot tell which country it is.
     */
    public record Parts(String countryCode, String nationalNumber, String region) {
    }

    /** Whether {@code value} already has the stored shape, {@link #E164}. Shape only. */
    public static boolean isE164(String value) {
        return value != null && E164_PATTERN.matcher(value.trim()).matches();
    }

    /**
     * The stored form of a typed number: separators dropped, checked for a calling code and for the
     * number of digits its country uses. "+65 9123-4567" → "+6591234567".
     *
     * @throws BusinessException when the number is blank, has no calling code, contains letters,
     *                           names no country, or has too few or too many digits for its country
     */
    public static String toE164(String input) {
        String value = StringUtils.trimToNull(input);
        if (value == null || !TYPED_NUMBER.matcher(value).matches()) {
            throw new BusinessException(E164_MESSAGE);
        }
        Phonenumber.PhoneNumber number = parse(value).orElseThrow(() -> new BusinessException(E164_MESSAGE));
        if (!UTIL.isPossibleNumber(number)) {
            throw new BusinessException("{0} has the wrong number of digits for its country code.", value);
        }
        return UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
    }

    /**
     * The ISO 3166-1 alpha-2 country of a number with its calling code. Longest match on the number
     * ranges, so +886 is TW and not CN, and +1 787 is PR and not US.
     *
     * @throws BusinessException when the number does not parse, or its calling code is shared and
     *                           the bundled ranges cannot place it
     */
    public static String regionOf(String number) {
        Parts parts = split(number).orElseThrow(() -> new BusinessException(
                "Invalid E.164 phone number {0}: {1}", number, E164_MESSAGE));
        if (parts.region() == null) {
            throw new BusinessException("Unable to determine the country of phone number {0}", number);
        }
        return parts.region();
    }

    /**
     * The parts of a number with its calling code, read leniently — for showing a stored number,
     * not for checking one: empty when it does not parse, has no calling code or contains letters.
     */
    public static Optional<Parts> split(String number) {
        String value = StringUtils.trimToNull(number);
        if (value == null || !TYPED_NUMBER.matcher(value).matches()) {
            return Optional.empty();
        }
        return parse(value).map(parsed -> {
            String region = UTIL.getRegionCodeForNumber(parsed);
            return new Parts(String.valueOf(parsed.getCountryCode()), UTIL.getNationalSignificantNumber(parsed),
                    region == null || PhoneNumberUtil.REGION_CODE_FOR_NON_GEO_ENTITY.equals(region) ? null : region);
        });
    }

    private static Optional<Phonenumber.PhoneNumber> parse(String value) {
        try {
            // No default region: a number is read only by the calling code it carries.
            return Optional.of(UTIL.parse(value, null));
        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }
}
