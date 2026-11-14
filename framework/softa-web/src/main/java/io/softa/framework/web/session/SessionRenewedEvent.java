package io.softa.framework.web.session;

/**
 * Published when {@link SessionStore} slides a session's expiry — at most about once a day for a
 * client in use. The session now lives until {@link SessionStore#TTL_SECONDS} from this moment, so
 * whatever keeps a copy of "this session is alive" can refresh it here instead of on every request.
 *
 * @param sessionId the session id
 * @param session   what the session stands for
 */
public record SessionRenewedEvent(String sessionId, SessionRecord session) {
}
