package io.softa.starter.user.dto;

/**
 * What the /join landing page may show for a token, and why.
 *
 * <p>PRD §3.0 checks five things IN ORDER and lands the first four on the same "link expired"
 * screen. Collapsing them into one boolean would be enough for the UI but not for support: the
 * reason is what tells HR whether to re-send, to re-invite, or to tell the person they already
 * joined. So the reason travels even though the screen is shared.
 *
 * @param usable       whether the person may proceed to identity verification
 * @param reason       why not, when {@code usable} is false
 * @param companyName  the inviting company, for the verification screen
 * @param employeeName the invitee's name, shown on the confirm-join screen so they can spot a
 *                     mis-addressed invitation before accepting it (PRD §3.3)
 * @param maskedEmail  masked work email, or null when the invitation had none
 * @param maskedMobile masked work mobile, or null when the invitation had none
 */
public record JoinEntry(boolean usable, Reason reason, String companyName, String employeeName,
                        String maskedEmail, String maskedMobile, String dialCode, String countryName) {

    /** Why a link cannot be used. The first four all render as "this link has expired". */
    public enum Reason {
        /** Token unknown, or superseded by a re-send / revoke. */
        LINK_INVALID,
        /** Past its 7-day life. */
        LINK_EXPIRED,
        /** Already completed — the person joined, possibly on another device. */
        ALREADY_JOINED,
        /** The membership was closed or frozen while the invitation was outstanding (E11). */
        MEMBERSHIP_CLOSED,
        /** Usable. */
        NONE
    }

    public static JoinEntry rejected(Reason reason) {
        return new JoinEntry(false, reason, null, null, null, null, null, null);
    }

    public static JoinEntry usable(String companyName, String employeeName,
            String maskedEmail, String maskedMobile) {
        return usable(companyName, employeeName, maskedEmail, maskedMobile, null, null);
    }

    /**
     * @param maskedMobile "+65 912****67" — the dial code, then the masked national number
     * @param dialCode     the mobile's country dial code, "+65"
     * @param countryName  that country's name, for the page to show beside it
     */
    public static JoinEntry usable(String companyName, String employeeName,
            String maskedEmail, String maskedMobile, String dialCode, String countryName) {
        return new JoinEntry(true, Reason.NONE, companyName, employeeName, maskedEmail, maskedMobile,
                dialCode, countryName);
    }
}
