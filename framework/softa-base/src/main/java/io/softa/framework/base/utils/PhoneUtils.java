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
     * The stored form of a typed number: separators dropped, and the national trunk prefix typed after the calling
     * code dropped ("+64 021 123 4678" → "+64211234678"), checked for a calling code and for the number of digits its
     * country uses. "+65 9123-4567" → "+6591234567".
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
     * The stored form of a number when it is one, the value unchanged otherwise — for normalizing what is written
     * to a phone field or looked up by it, where refusing is someone else's job. A number with its calling code and
     * the right number of digits for its country comes back as {@link #toE164} gives it: separators dropped, and the
     * national trunk prefix a person types after the calling code dropped too — "+64 021 123 4678" and
     * "+640211234678" are "+64211234678", as "+44 07700 900123" is "+447700900123". Which digit is a trunk prefix,
     * and where it is part of the number (Italy keeps its 0), is libphonenumber's metadata for the country, not a
     * list kept here. Anything else — blank, no calling code, letters, too few or too many digits — is returned as it
     * was, for the field's own pattern to judge.
     */
    public static String canonical(String input) {
        String value = StringUtils.trimToNull(input);
        if (value == null || !TYPED_NUMBER.matcher(value).matches()) {
            return input;
        }
        return parse(value)
                .filter(UTIL::isPossibleNumber)
                .map(number -> UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.E164))
                .orElse(input);
    }

    /**
     * The spelling a number had when its national trunk prefix was kept after the calling code — "+640211234678"
     * for "+64 21 123 4678" — or empty when its country has no trunk prefix (Singapore) or keeps it as part of the
     * number (Italy). For finding rows written before numbers were stored in {@link #canonical} form; never a form
     * to store. The prefix is libphonenumber's metadata for the country, and the result is only given when
     * {@link #canonical} reads it back as the same number, so it never names a different one.
     */
    public static Optional<String> withTrunkPrefix(String input) {
        String e164 = canonical(input);
        if (!isE164(e164)) {
            return Optional.empty();
        }
        return parse(e164).flatMap(number -> {
            String region = UTIL.getRegionCodeForCountryCode(number.getCountryCode());
            String prefix = StringUtils.trimToNull(UTIL.getNddPrefixForRegion(region, true));
            if (prefix == null) {
                return Optional.empty();
            }
            String legacy = "+" + number.getCountryCode() + prefix + UTIL.getNationalSignificantNumber(number);
            return e164.equals(canonical(legacy)) ? Optional.of(legacy) : Optional.empty();
        });
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
