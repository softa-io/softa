package io.softa.framework.web.session;

/**
 * Stable error codes every signed-in endpoint can answer with, carried as {@code errorCode} on the
 * error response.
 */
public final class SessionErrorCodes {

    /** The session does not exist or has expired: the client goes back to the login page. */
    public static final String SESSION_EXPIRED = "SESSION_EXPIRED";

    /**
     * The session's account became Frozen or Deactivated (or its tenant stopped being active): only
     * this tenant's session ends, and the client picks a company again.
     */
    public static final String ORG_UNAVAILABLE = "ORG_UNAVAILABLE";

    private SessionErrorCodes() {
    }
}
