package io.softa.starter.user.service;

import io.softa.framework.web.session.SessionRevokeReason;

/**
 * Ending sessions by account or by person, on top of the framework's {@code SessionStore}.
 *
 * <p>"A person's sessions" is the union of the sessions of every account the person holds; there is
 * no per-person index to keep in step (see {@code SessionStore}).
 */
public interface UserSessionService {

    /**
     * The session id of the current request (cookie or {@code X-Session-Id}), or null outside a
     * request or when it carries none.
     */
    String currentSessionId();

    /**
     * End every session of one account — inside a transaction, once it commits (then returning 0).
     */
    int revokeAccount(Long accountId, SessionRevokeReason reason);

    /**
     * End every session of every account of this person, except {@code keepSessionId} when given —
     * the session the change was made from. Also voids every sign-in step this person has started
     * but not finished (see {@link #credentialsChangedSince}). Inside a transaction the sessions end
     * once it commits (then returning 0).
     */
    int revokePerson(Long profileId, String keepSessionId, SessionRevokeReason reason);

    /**
     * Whether this person's sessions were ended for a credential change after {@code issuedAtMillis}.
     * A single-use sign-in token issued before that must not be redeemed: whoever authenticated with
     * the old number or password would otherwise still get a fresh session out of it.
     */
    boolean credentialsChangedSince(Long profileId, long issuedAtMillis);
}
