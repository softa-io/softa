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

    /** End every session of one account. */
    int revokeAccount(Long accountId, SessionRevokeReason reason);

    /**
     * End every session of every account of this person, except {@code keepSessionId} when given —
     * the session the change was made from.
     */
    int revokePerson(Long profileId, String keepSessionId, SessionRevokeReason reason);
}
