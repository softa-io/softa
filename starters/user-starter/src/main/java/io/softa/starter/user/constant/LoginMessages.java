package io.softa.starter.user.constant;

/**
 * The refusals a signed-out visitor can be shown, where more than one entry point has to word one
 * the same way.
 *
 * <p>Each sentence is also its own i18n key — {@code BusinessException} resolves the message
 * through {@code I18n.get}, keyed by the English text. A copy of one of these that drifted by a
 * character would stop resolving, and the app's override would silently fall back to the English
 * default; that is what these constants exist to prevent, not mere repetition.
 *
 * <p>They name an <em>administrator</em>, which is as specific as a framework can honestly be. An
 * app that would rather send people to a particular desk — an HR team, a service line — overrides
 * the sentence through i18n rather than editing it here.
 */
public final class LoginMessages {

    /**
     * No identity holds this email as a login identifier.
     *
     * <p>Deliberately not anti-enumeration; see the guards that raise it for the reasoning and for
     * where the opposite choice is still the right one.
     */
    public static final String EMAIL_NOT_LINKED =
            "This email is not linked to any account. Please contact your administrator.";

    /** The mobile twin of {@link #EMAIL_NOT_LINKED}. */
    public static final String MOBILE_NOT_LINKED =
            "This mobile number is not linked to any account. Please contact your administrator.";

    private LoginMessages() {
        // utility class — no instances
    }
}
