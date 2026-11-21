package io.softa.starter.user.util;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.utils.PhoneUtils;

/**
 * The one spelling a login identifier has: trimmed, lowercased, and for a mobile number stripped of
 * the separators people type into it.
 *
 * <p>Applied wherever an identifier is STORED, LOOKED UP or HASHED, so that the three can never
 * disagree about which string names a person. They did disagree: the unknown-identifier counter
 * hashed the trimmed, lowercased form while the identity lookup queried the raw one, so an
 * identifier typed with a leading space always resolved to nobody. Seven guesses at {@code " x"}
 * then one at {@code "x"}: an unknown {@code x} counted to eight and showed the countdown, a known
 * one landed on its own fresh counter and did not — an existence oracle that no collation setting
 * could close, because the space never reached the database.
 *
 * <p>Lowercasing is what makes two spellings of one mailbox one identifier. For a mobile the
 * equivalent is {@link #collapseMobile}: {@code +65 9123-4567} and {@code +6591234567} are one
 * number, and a code sent to one had to resolve the identity stored under the other — a person
 * whose identifier HR seeded with the spaces the form allowed could otherwise never sign in by
 * mobile, and two rows holding one number in two spellings read as unshared to the shared-contact
 * guard, which exists precisely to catch a number held twice. Work contacts ({@code UserAccount.email}
 * / {@code mobile}) keep HR's case and are NOT lowercased here — but they do go through
 * {@code collapseMobile}, so the equality queries on those columns find one number however it was
 * typed. This lives in {@code util} because the invitation's {@code JoinContacts} in {@code dto}
 * compares against a stored identifier and must apply the identical rule, not a copy.
 */
public final class LoginIdentifiers {

    /**
     * What a phone number looks like once trimmed: a dial code, or nothing but digits and the
     * separators people put between them (space, hyphen, non-breaking space). An email never
     * matches, so its spaces — which would make it a different mailbox — are left alone.
     */
    private static final Pattern MOBILE_SHAPE = Pattern.compile("\\+.*|[0-9 \\-\\u00A0]+");
    private static final Pattern MOBILE_SEPARATORS = Pattern.compile("[ \\-\\u00A0]");

    private LoginIdentifiers() {
    }

    /**
     * The canonical form, or null for a blank input: an email lowercased; a mobile with its calling code in the
     * stored form ({@link PhoneUtils#canonical}) — separators dropped, and the trunk prefix typed after the calling
     * code dropped too, so "+64 021 123 4678" and "+64211234678" are one identifier; any other mobile-shaped value
     * with its separators dropped.
     */
    public static String normalize(String identifier) {
        String value = StringUtils.trimToNull(identifier);
        return value == null ? null : PhoneUtils.canonical(collapseMobile(value)).toLowerCase(Locale.ROOT);
    }

    /**
     * Trimmed and lowercased only — a mobile keeps the separators it was typed with. What a caller
     * hands the lookups when it wants {@link #loginSpellings} to include the typed spelling and not
     * just the canonical one; see there for why that spelling still matters.
     */
    public static String typedForm(String identifier) {
        String value = StringUtils.trimToNull(identifier);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    /**
     * Every spelling a login identifier may be STORED under, canonical form first; empty for a blank.
     *
     * <p>Rows seeded before the separator fold hold a mobile as it was typed ("+65 9123-4567"), and
     * no migration rewrites them — ops SQL is out of band and may never run. A lookup that asked
     * only for the collapsed form would then miss exactly those rows: the person cannot sign in or
     * reset by mobile, /join on an unbound row mints a duplicate identity, and the shared-contact
     * guard misses a legacy holder. So a mobile is asked for under both the collapsed form and the
     * form it was typed in, which is what such a row was written as. An email has one spelling —
     * its canonical form — since nothing inside it was ever folded.
     */
    public static List<String> loginSpellings(String identifier) {
        String typed = typedForm(identifier);
        // The collapsed form too: a row written before numbers were stored in one spelling may hold the trunk
        // prefix ("+640211234678"), and is found when the person types it as it was stored.
        return spellings(normalize(identifier), collapseMobile(typed), typed);
    }

    /**
     * The same set for a work contact ({@code UserAccount.email} / {@code mobile}): collapsed and
     * trimmed-as-typed, case kept, because those columns keep HR's case and rely on the collation.
     */
    public static List<String> workContactSpellings(String contact) {
        String trimmed = StringUtils.trimToNull(contact);
        String collapsed = collapseMobile(trimmed);
        return spellings(PhoneUtils.canonical(collapsed), collapsed, trimmed);
    }

    private static List<String> spellings(String... forms) {
        return Stream.of(forms).filter(StringUtils::isNotBlank).distinct().toList();
    }

    /**
     * A phone-shaped value with its internal spaces, hyphens and non-breaking spaces removed; any
     * other value (an email, a blank) unchanged. Case is never touched here, so the work-contact
     * columns can apply it without taking on the lowercasing that belongs to login identifiers only.
     */
    public static String collapseMobile(String value) {
        if (value == null || !MOBILE_SHAPE.matcher(value).matches()) {
            return value;
        }
        return MOBILE_SEPARATORS.matcher(value).replaceAll("");
    }
}
