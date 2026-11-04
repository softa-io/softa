package io.softa.starter.user.constant;

import io.softa.framework.base.exception.BusinessException;

/**
 * Stable error codes of the login, join and account flows, carried as {@code errorCode} on the error
 * response. Clients show their own copy per code instead of the server's message. The two session
 * codes every signed-in endpoint can answer with live in {@code SessionErrorCodes}.
 */
public final class AccountErrorCodes {

    /** The mobile or email is not linked to any account. */
    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    /** Wrong password; from the seventh failure {@code remainingAttempts} comes with it. */
    public static final String PASSWORD_INCORRECT = "PASSWORD_INCORRECT";
    /** Password login is locked; a verification code still works. */
    public static final String PASSWORD_LOCKED = "PASSWORD_LOCKED";
    /** Wrong verification code. */
    public static final String CODE_INCORRECT = "CODE_INCORRECT";
    /** The verification code expired, or none was sent. */
    public static final String CODE_EXPIRED = "CODE_EXPIRED";
    /** Five wrong tries: the code is dead, request a new one. */
    public static final String CODE_INVALIDATED = "CODE_INVALIDATED";
    /** Ten consecutive wrong tries: verification on this number / address is blocked for a while. */
    public static final String CODE_BLOCKED = "CODE_BLOCKED";
    /** Codes requested too often. */
    public static final String TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS";
    /** The new login mobile is already another person's. */
    public static final String MOBILE_TAKEN = "MOBILE_TAKEN";
    /** The new login mobile is the current one. */
    public static final String MOBILE_UNCHANGED = "MOBILE_UNCHANGED";
    /** The enterprise email is already another person's (outside Deactivated accounts). */
    public static final String EMAIL_TAKEN = "EMAIL_TAKEN";
    /** The person already holds a live account in this company. */
    public static final String ALREADY_JOINED = "ALREADY_JOINED";
    /**
     * The invitation names a real person and carries no mobile, so the email alone cannot prove the
     * one joining is them: HR adds the personal mobile and sends the invitation again.
     */
    public static final String JOIN_NEEDS_MOBILE = "JOIN_NEEDS_MOBILE";
    /** The contact identifies more than one person, so nobody can be signed in by it. */
    public static final String CONTACT_SHARED = "CONTACT_SHARED";
    /** A password is already set, so the first-password step does not apply. */
    public static final String PASSWORD_ALREADY_SET = "PASSWORD_ALREADY_SET";
    /** The APP asked to enter a company before setting the first password it owes. */
    public static final String PASSWORD_REQUIRED = "PASSWORD_REQUIRED";
    /** A step ticket of a self-service change (password, login mobile) expired, was used, or is not this step's. */
    public static final String TICKET_EXPIRED = "TICKET_EXPIRED";
    /** The person has no login mobile, so no code can be sent to it. */
    public static final String MOBILE_NOT_SET = "MOBILE_NOT_SET";
    /** A consultant tried to sign in on the APP; consultant accounts sign in on the web only. */
    public static final String CONSULTANT_APP_UNSUPPORTED = "CONSULTANT_APP_UNSUPPORTED";
    /** A platform account tried to sign in on the APP; platform accounts sign in on the web only. */
    public static final String PLATFORM_APP_UNSUPPORTED = "PLATFORM_APP_UNSUPPORTED";
    /**
     * Unbind &amp; Re-invite refused: the record's personal mobile and work email are both what the
     * last invitation went to, so re-inviting would reach the same wrong person again.
     */
    public static final String INVITATION_UNCHANGED = "INVITATION_UNCHANGED";
    /** Signed in, but not linked to any company: nothing to enter and nothing pending. */
    public static final String NO_COMPANY = "NO_COMPANY";
    /** Signed in, but holding nothing except invitations not accepted yet. */
    public static final String INVITATION_PENDING = "INVITATION_PENDING";
    /** A consultant whose authorizations have all ended: the platform, not a company, can extend them. */
    public static final String CONSULTANT_NO_ACCESS = "CONSULTANT_NO_ACCESS";
    /** The single-use sign-in token expired or was already used. */
    public static final String AUTH_TOKEN_EXPIRED = "AUTH_TOKEN_EXPIRED";

    private AccountErrorCodes() {
    }

    /** A business refusal carrying one of these codes. */
    public static BusinessException refusal(String code, String message) {
        BusinessException e = new BusinessException(message);
        e.withErrorCode(code);
        return e;
    }
}
