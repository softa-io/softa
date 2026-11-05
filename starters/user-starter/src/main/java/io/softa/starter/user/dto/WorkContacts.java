package io.softa.starter.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The work contacts an employee record currently holds.
 *
 * <p>The employee record is the single source of these, so Reset User and Unbind &amp;
 * Re-invite both read them from there rather than taking them from the caller — and both echo them
 * read-only first, because the way to change a number is to edit the record, not the account.
 *
 * @param email          the record's work email, or null when it has none
 * @param mobile         the record's work mobile, or null when it has none
 * @param personalMobile the record's personal mobile — what Reset User makes the login mobile and
 *                       Unbind &amp; Re-invite invites on; null when not asked for or none
 * @param loginMobile    the login mobile of the person the account belongs to now; null when not
 *                       asked for, or nobody is bound yet
 */
@Schema(description = "The work contacts held by an account's employee record")
public record WorkContacts(String email, String mobile, String personalMobile, String loginMobile) {

    /** The work email and work mobile only. */
    public WorkContacts(String email, String mobile) {
        this(email, mobile, null, null);
    }

    /** Neither channel — an account with no employee record behind it, or a record with no contacts. */
    public static WorkContacts none() {
        return new WorkContacts(null, null);
    }

    /** The same work contacts, with the personal and login mobile alongside. */
    public WorkContacts withMobiles(String personalMobile, String loginMobile) {
        return new WorkContacts(email, mobile, personalMobile, loginMobile);
    }

    /** Whether there is anything to reach this person on. */
    public boolean any() {
        return email != null || mobile != null;
    }
}
