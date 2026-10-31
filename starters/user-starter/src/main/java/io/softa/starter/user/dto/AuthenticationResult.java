package io.softa.starter.user.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.softa.framework.base.context.UserInfo;

/**
 * What authentication now answers: WHO you are, and what happens next.
 *
 * <p>It used to answer only "here is your UserInfo", which was enough while a person had exactly
 * one account. With several memberships possible, the caller needs more before a session can be
 * issued, and each is a decision the client acts on:
 *
 * <ul>
 *   <li>{@code profileId} — the authenticated person, for display. It is NOT what authorizes the
 *       follow-up calls: {@code authToken} is. profileId is not a secret, so a call that trusted
 *       it would let anyone name a stranger's id and be issued that stranger's session.</li>
 *   <li>{@code authToken} — a single-use, short-lived proof that authentication just succeeded for
 *       this person, present ONLY when a choice is pending (a resolved result already carries its
 *       session). {@code selectTenant} / {@code listTenants} require it and read the person from
 *       it, so the second step of a multi-company login cannot be reached without passing the
 *       first.</li>
 *   <li>{@code mustSetPassword} — this person has no password yet (they arrived by invitation or
 *       code). The client must force Set Password, since a password-less person cannot return
 *       through the password route.</li>
 *   <li>{@code tenants} + {@code userInfo} — either exactly one enterable membership resolved
 *       (userInfo set, session issued) or a choice is needed (tenants listed, userInfo null).</li>
 *   <li>{@code signInRequired} — the membership was activated but NOTHING was issued: no session,
 *       no authToken. Only /join answers this, for a person who can already sign in some other way
 *       and has no password (see {@code LoginServiceImpl.confirmJoin}). The client sends them to
 *       the login page; every other result carries {@code false}.</li>
 * </ul>
 *
 * @param profileId       the authenticated person (display only; authToken is the credential)
 * @param userInfo        the session payload when a single membership resolved; null when a choice
 *                        is pending
 * @param tenants       the options when a choice is pending; empty when one already resolved
 * @param mustSetPassword whether the client must force the Set Password step first
 * @param authToken       single-use proof of authentication for the company step; null once resolved
 * @param signInRequired  kept on the wire for older clients; always false now that /join verifies the
 *                        person before confirming (see {@code LoginServiceImpl.confirmJoin})
 * @param sessionToken    the session id when a session was issued, for the APP to send as
 *                        {@code X-Session-Id}; null when no session was issued
 * @param autoEnterAccountId the account the entry rule enters directly (the only enterable one, or
 *                        the one entered last time); null when the client should show the picker
 * @param noEnterableReason why there is nowhere to enter, when that is the case
 * @param prefill         the contact a just-joined person signs in with, for the "download the APP"
 *                        page; only {@code confirmJoin} sets it
 */
public record AuthenticationResult(Long profileId, UserInfo userInfo, List<MembershipOption> tenants,
                                   boolean mustSetPassword, String authToken, boolean signInRequired,
                                   String sessionToken, Long autoEnterAccountId,
                                   NoEnterableReason noEnterableReason, JoinPrefill prefill) {

    /** Why an authenticated person has no company to enter. */
    public enum NoEnterableReason {
        /** Every membership is Frozen or Deactivated. */
        DEACTIVATED,
        /** Nothing but invitations that have not been confirmed yet. */
        NOT_JOINED
    }

    /**
     * The contact the person just verified, in full, so the page after /join can prefill the login
     * screen. Only ever given to the person who verified it.
     *
     * @param channel {@code mobile} or {@code email}
     * @param value   the full number or address
     */
    public record JoinPrefill(String channel, String value) {
    }

    public static AuthenticationResult resolved(Long profileId, UserInfo userInfo, boolean mustSetPassword) {
        // Resolved means a session is being issued now, so no company step follows and no token is
        // needed — carrying one would be a live credential left lying in the response for nothing.
        return new AuthenticationResult(profileId, userInfo, List.of(), mustSetPassword, null, false,
                null, userInfo == null ? null : userInfo.getUserId(), null, null);
    }

    public static AuthenticationResult choicePending(Long profileId, List<MembershipOption> tenants,
            boolean mustSetPassword, String authToken) {
        return choicePending(profileId, tenants, mustSetPassword, authToken, null, null);
    }

    /**
     * No session yet: either a choice is pending, a password must be set first (and the entry rule
     * already knows where it will go — {@code autoEnterAccountId}), or there is nowhere to go
     * ({@code noEnterableReason}).
     */
    public static AuthenticationResult choicePending(Long profileId, List<MembershipOption> tenants,
            boolean mustSetPassword, String authToken, Long autoEnterAccountId,
            NoEnterableReason noEnterableReason) {
        return new AuthenticationResult(profileId, null, List.copyOf(tenants), mustSetPassword, authToken, false,
                null, autoEnterAccountId, noEnterableReason, null);
    }

    /** The same result, carrying the session that was just issued for it. */
    public AuthenticationResult withSessionToken(String token) {
        return new AuthenticationResult(profileId, userInfo, tenants, mustSetPassword, authToken, signInRequired,
                token, autoEnterAccountId, noEnterableReason, prefill);
    }

    /** The same result, carrying the contact to prefill on the login screen. */
    public AuthenticationResult withPrefill(JoinPrefill joinPrefill) {
        return new AuthenticationResult(profileId, userInfo, tenants, mustSetPassword, authToken, signInRequired,
                sessionToken, autoEnterAccountId, noEnterableReason, joinPrefill);
    }

    /**
     * Joined, and nothing else. No userInfo (so {@link #isResolved()} is false and no session is
     * issued), no authToken (so the company step cannot be reached from this response), no
     * profileId (there is nothing for the client to act on with it), and mustSetPassword false —
     * the password is set from the person's own session, not from here.
     */
    public static AuthenticationResult requireSignIn() {
        return new AuthenticationResult(null, null, List.of(), false, null, true, null, null, null, null);
    }

    /** Whether a session can be issued straight away. */
    // Named on the wire on purpose: the client branches on "resolved", and a bean-convention
    // accident (isX) is too thin a thing for that contract to hang from.
    @JsonProperty("resolved")
    public boolean isResolved() {
        return userInfo != null;
    }
}
