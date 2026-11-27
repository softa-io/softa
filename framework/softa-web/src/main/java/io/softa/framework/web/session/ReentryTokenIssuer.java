package io.softa.framework.web.session;

import java.time.LocalDateTime;

/**
 * Issues the single-use sign-in token a client re-enters with after its organisation became
 * unavailable mid-session.
 *
 * <p>ORG_UNAVAILABLE ends the session for ONE organisation; the person is still who they were. With
 * this token the client can list the person's other organisations and pick one, instead of making
 * them sign in again — which would leave nothing to tell ORG_UNAVAILABLE and SESSION_EXPIRED apart.
 * Optional: a deployment without a login module has nothing to re-enter with, and answers without it.
 */
public interface ReentryTokenIssuer {

    /**
     * The token stands for the sign-in behind the ended session, not for the moment it is asked for:
     * it is offered again every time the ended session is presented, for as long as the session would
     * have lived, so dated now it would outlive any change of password or login mobile made since. Dated
     * at the sign-in, such a change voids it like every other sign-in token.
     *
     * @param profileId      the person whose session just ended
     * @param authenticatedAt when that session was issued — the person last proved who they are then;
     *                        null when unknown, which no credential change survives
     * @return the token, or null when none can be issued
     */
    String issueReentryToken(Long profileId, LocalDateTime authenticatedAt);
}
