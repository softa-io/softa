package io.softa.starter.user.service;

import io.softa.framework.base.context.UserInfo;
import java.util.List;

import io.softa.starter.user.dto.AuthenticationResult;
import io.softa.starter.user.dto.JoinVerification;
import io.softa.starter.user.dto.InvitationInfo;
import io.softa.starter.user.dto.MembershipOption;

/**
 * UserAccount Model Service Interface
 */
public interface LoginService {

    /**
     * Send an email verification code to an address that an account can sign in with.
     *
     * <p>Refuses an address no login identifier resolves to, rather than reporting success for a
     * code nobody will receive. The refusal names the reason, which does disclose whether an
     * address is in use — see the implementation for why that is the deliberate choice here.
     *
     * <p>Not the path for /join: an invitee may legitimately have no identity yet.
     *
     * @param email Email address
     */
    void sendEmailCode(String email);

    /**
     * Send a mobile verification code. The mobile twin of {@link #sendEmailCode(String)}, with the
     * same existence guard and the same caveats.
     *
     * @param mobile Mobile number
     */
    void sendMobileCode(String mobile);

    /**
     * Authenticate by one-time code sent to a login identifier (email or dial-code mobile).
     *
     * <p>Replaces the per-channel {@code loginByEmailCode} / {@code loginByMobileCode}: the code
     * was sent to an identifier, and which KIND it is stopped mattering once identifiers became
     * properties of the person rather than of a company's account.
     */
    AuthenticationResult authenticateByCode(String identifier, String code);

    /** Authenticate by password against a login identifier. */
    AuthenticationResult authenticateByPassword(String identifier, String password);

    /** Whether this person still has to set a password (arrived by invitation or code only). */
    /**
     * Sends a verification code to whichever channel an invitation names, resolved from its token.
     *
     * <p>Lives here rather than on the invitation service because code issuance and its rate limits
     * are a login concern, and the invitation service is already a dependency of this one.
     *
     * @param channel {@code "email"} or {@code "mobile"}
     */
    void sendJoinCode(String rawToken, String channel);

    /**
     * Resets a password using a verification code instead of an emailed link.
     *
     * <p>The link flow can only reach an email address, which left an employee who was invited by
     * work mobile — a normal case — with no way to reset at all. A code proves control of the
     * identifier, which is the same thing the link proves, so this is not a weaker gate.
     *
     * <p>Unlike the first-password paths this one deliberately DOES overwrite an existing password:
     * that is what a reset is. The proof is the code, verified here against the identifier the
     * person named.
     */
    void resetPasswordByCode(String identifier, String code, String newPassword);

    /**
     * Proves identity on the /join flow: verifies the code against the invitation's OWN address and
     * returns the person behind it, creating that person if this is their first company.
     *
     * <p>Separate from {@link #authenticateByCode} because that path resolves an existing person by
     * login identifier and then runs the company resolution. A first-time invitee has neither — no
     * profile, and no ACTIVE membership until they confirm — so it would reject exactly the people
     * this flow serves.
     *
     * @param channel {@code "email"} or {@code "mobile"} — which address the code was sent to
     */
    JoinVerification verifyJoinCode(String rawToken, String channel, String code);

    /**
     * Sets a first password during /join, where no session exists yet.
     *
     * <p>Authorized by the invitation rather than by a session, so it is deliberately narrow: it
     * refuses unless the profile's own login identifier is one the invitation names AND that
     * profile has no password. Without both checks a link-holder could name any profileId and
     * overwrite a stranger's password. A bound row's person who can already sign in some other
     * way (a live login identifier the invitation was not addressed to) is refused too and sets
     * the password in-session: the code proved control of the work address, not of the person.
     *
     * @param proof the value {@link #verifyJoinCode} returned; refused unless it was minted for this
     *              very invitation and person, so holding the link is not enough to reach the write
     */
    void setJoinPassword(String rawToken, Long profileId, String newPassword, String proof);

    /** Whether this person still has to set a password (arrived by invitation or code only). */
    boolean mustSetPassword(Long profileId);

    /**
     * Accept the invitation — bind the person, activate the membership — and decide where they land.
     *
     * <p>Not simply "the membership they just joined": someone who already belonged elsewhere now
     * has two, and must still choose. Reusing the same resolution as authentication is what keeps
     * the two entry points from disagreeing.
     *
     * <p>Not always a sign-in, either. For a row that already belonged to a person, the code proved
     * control of the WORK address on the row — a mailbox the company holds — not of the person. When
     * that person can sign in some other way and has no password, the membership is activated (HR
     * meant it) but the result is {@link AuthenticationResult#requireSignIn()}: no session and no
     * pre-auth token, because either would be a session for whoever holds the mailbox.
     *
     * @param proof the value {@link #verifyJoinCode} returned; spent here
     */
    AuthenticationResult confirmJoin(String rawToken, Long profileId, String proof);

    /**
     * Generate a new session ID for a user
     *
     * @param userId User ID
     * @return Session ID
     */
    String generateSessionId(Long userId);

    /**
     * The tenants this person may log into, for the "choose your company" step.
     *
     * <p>Authentication answers WHO; this answers WHERE. They are separate calls because one
     * person can belong to several tenants while a session must carry exactly one membership.
     *
     * @param profileId the authenticated person
     * @return their memberships, off-boarded ones excluded, non-ACTIVE ones listed but flagged
     *         unselectable (so a frozen company is visibly present rather than silently missing)
     */
    List<MembershipOption> listTenants(String authToken);

    /**
     * Resolve which membership an authenticated person lands in.
     *
     * <p>0 → refuse; exactly 1 → that one (so a single-company person sees no extra step, which
     * is today's behaviour unchanged); more than 1 → refuse and let the caller present
     * {@link #listTenants}.
     *
     * @throws io.softa.framework.base.exception.BusinessException when the person belongs to no
     *         company, or to several and must choose
     */
    Long resolveSingleMembership(Long profileId);

    /**
     * Verify that this membership really belongs to this person, then hand back its account id
     * for session issuance.
     *
     * <p>Authorized by the pre-auth {@code authToken}, not by a client-supplied profileId: it
     * names the person server-side, so the company step cannot be reached without having passed
     * authentication. The membership must be one that person holds (the ownership check), or naming
     * any accountId would mint a session in a company they are not a member of. Single-use — the
     * token is consumed on success.
     */
    AuthenticationResult selectTenant(String authToken, Long accountId);

    /**
     * The same company list the login picker shows, for a person who is ALREADY signed in — the
     * header's tenant switcher.
     *
     * <p>Authorized by the current session, so the caller names no person: the account the session
     * maps to is resolved to its {@code profileId} and the picker's own resolution runs from there.
     * The company they are in now is included; it is the one showing as current in the switcher.
     *
     * @param currentAccountId the UserAccount the current session maps to
     */
    List<MembershipOption> myTenants(Long currentAccountId);

    /**
     * Move an existing session to another of the same person's tenants.
     *
     * <p>The authorization is the CURRENT SESSION, never a pre-auth token: a signed-in person
     * switching tenants has already authenticated, and accepting a token here would open a second
     * route to minting a session that does not pass through the login flow. The named membership
     * must be one the session's person holds and must be selectable — the same two checks
     * {@link #selectTenant} makes, for the same reason.
     *
     * <p>Returns the resolved result; the CALLER issues the session (and drops the previous one),
     * because cookie handling belongs to the controller.
     *
     * @param currentAccountId the UserAccount the current session maps to
     * @param accountId        the membership to move to
     */
    AuthenticationResult switchTenant(Long currentAccountId, Long accountId);

    /**
     * Forgot password — issue a self-service password-reset token and email the set-password link.
     *
     * @param email registered email
     */
    void forgetPassword(String email);

    /**
     * Set the password via a token (invitation-accept or forgot-password reset).
     *
     * @param token       the emailed one-time token
     * @param newPassword the new password
     */
    void resetPassword(String token, String newPassword);

    /**
     * Validate a token for the public set-password page.
     *
     * @param token the emailed one-time token
     * @return validity + the email to greet the holder (no leak of why an invalid token failed)
     */
    InvitationInfo inviteInfo(String token);
}