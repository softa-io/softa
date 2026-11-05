package io.softa.framework.web.session;

/**
 * Published once for every session {@link SessionStore} removes, after it is removed.
 *
 * @param sessionId the removed session id
 * @param session   what the session stood for
 * @param reason    why it was removed
 */
public record SessionRevokedEvent(String sessionId, SessionRecord session, SessionRevokeReason reason) {
}
