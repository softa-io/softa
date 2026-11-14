package io.softa.framework.web.session;

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
     * @param profileId the person whose session just ended
     * @return the token, or null when none can be issued
     */
    String issueReentryToken(Long profileId);
}
