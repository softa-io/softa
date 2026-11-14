package io.softa.framework.web.session;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.exception.JSONException;
import io.softa.framework.base.utils.UUIDUtils;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.web.utils.CookieUtils;

/**
 * The one place sessions are written, read, slid and removed.
 *
 * <p>Two keys:
 * <ul>
 *   <li>{@code session:{sessionId}} — the {@link SessionRecord}, expiring {@link #TTL_SECONDS} after
 *       the last request that slid it;</li>
 *   <li>{@code account-sessions:{accountId}} — the ids of that account's sessions, so every session
 *       of an account (or, account by account, of a person) can be found and removed. Its TTL follows
 *       the newest slide of any of its sessions.</li>
 * </ul>
 * There is deliberately no per-person index: "this person's sessions" is the union over the
 * person's accounts, so there is no second copy to drift out of step with the first.
 *
 * <p>The set may name a session that has already expired on its own; every reader treats such a
 * member as gone and drops it.
 */
@Slf4j
@Component
public class SessionStore {

    /** Sliding lifetime of a session: seven days after the last request. */
    public static final int TTL_SECONDS = RedisConstant.ONE_WEEK;

    /**
     * Slide only when less than this is left, so an active client writes Redis at most about once a
     * day instead of on every request.
     */
    static final int SLIDE_BELOW_SECONDS = TTL_SECONDS - RedisConstant.ONE_DAY;

    public static final String ACCOUNT_SESSIONS = "account-sessions:";

    /**
     * What is left of a session ended because its organisation became unavailable. A client fires
     * several requests at once; the first one ends the session, and without this the others would
     * find nothing and answer SESSION_EXPIRED — which the client is free to act on first, sending the
     * person to the login page instead of to their other organisations.
     */
    public static final String ORG_UNAVAILABLE_SESSIONS = "session-org-unavailable:";

    /** Long enough for the requests already in flight when the session ended. */
    static final int ORG_UNAVAILABLE_MARK_SECONDS = RedisConstant.ONE_MINUTES;

    private final CacheService cacheService;
    private final ApplicationEventPublisher eventPublisher;

    public SessionStore(CacheService cacheService, ApplicationEventPublisher eventPublisher) {
        this.cacheService = cacheService;
        this.eventPublisher = eventPublisher;
    }

    private static String sessionKey(String sessionId) {
        return RedisConstant.SESSION + sessionId;
    }

    private static String accountKey(Long accountId) {
        return ACCOUNT_SESSIONS + accountId;
    }

    /** The APP names its platform (ios / android) and its version on every request. */
    public static final String APP_PLATFORM_HEADER = "X-App-Platform";
    public static final String APP_VERSION_HEADER = "X-App-Version";

    /**
     * Whether the request says it comes from the APP: it carries {@code X-App-Platform} or
     * {@code X-App-Version}.
     *
     * <p>What a request says about itself, nothing more. It decides how a session travels and which
     * session a request may use, and the rules built on it (consultants and platform accounts on
     * the Web only, the APP's minimum version) are product rules: anyone can send these headers or
     * leave them out, so none of it is a security boundary.
     */
    public static boolean isAppRequest(HttpServletRequest request) {
        return StringUtils.isNotBlank(request.getHeader(APP_PLATFORM_HEADER))
                || StringUtils.isNotBlank(request.getHeader(APP_VERSION_HEADER));
    }

    /**
     * The session id a request carries, read from where its client keeps it: an APP request from
     * {@code X-Session-Id} only, a Web or H5 request from the cookie only. Null when it carries none
     * there.
     *
     * <p>One channel per client, so a session lifted from one place cannot be replayed through the
     * other: a Web session copied out of a cookie is not accepted as a header, and an APP token is
     * not accepted as a cookie. The APP keeps no cookies at all, and an APP request that happens to
     * carry one still has it ignored.
     */
    public static String sessionIdOf(HttpServletRequest request) {
        String sessionId = isAppRequest(request)
                ? request.getHeader(BaseConstant.SESSION_ID_HEADER)
                : CookieUtils.getCookie(request, BaseConstant.SESSION_ID);
        return StringUtils.isBlank(sessionId) ? null : sessionId;
    }

    /**
     * Whether a session may be used by this request: an APP session by the APP, a Web session by
     * Web or H5. A session is bound to the kind of client it was issued to.
     */
    public static boolean sameClient(SessionRecord session, HttpServletRequest request) {
        return session.isApp() == isAppRequest(request);
    }

    /**
     * Issue a new session for an account.
     *
     * @return the new session id, which is also the session token handed to the client
     */
    public String create(Long accountId, Long profileId, Long tenantId,
                         String platform, String appVersion, String deviceModel) {
        String sessionId = UUIDUtils.shortUUID22();
        SessionRecord session = new SessionRecord(accountId, profileId, tenantId,
                platform, appVersion, deviceModel, LocalDateTime.now());
        cacheService.save(sessionKey(sessionId), session, TTL_SECONDS);
        // Sessions that expired on their own are dropped from the index here, so it holds roughly
        // the live sessions instead of every session the account ever had.
        for (String member : cacheService.getSetMembers(accountKey(accountId))) {
            if (!cacheService.hasKey(sessionKey(member))) {
                cacheService.removeFromSet(accountKey(accountId), member);
            }
        }
        cacheService.addToSet(accountKey(accountId), sessionId, TTL_SECONDS);
        return sessionId;
    }

    /**
     * Read a session without sliding it.
     */
    public Optional<SessionRecord> peek(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(cacheService.get(sessionKey(sessionId), SessionRecord.class));
        } catch (JSONException e) {
            // A value written in another shape (the bare account id of earlier releases) is not a
            // session this release can honour; the release clears them, and any straggler simply
            // reads as expired. Only an unreadable value: a Redis timeout is not a reason to delete
            // a session that may be perfectly valid, and propagates.
            log.warn("Unreadable session value for {}; treating it as expired.", sessionId);
            cacheService.clear(sessionKey(sessionId));
            return Optional.empty();
        }
    }

    /**
     * Read a session and slide its expiry when it is due, announcing the slide with a
     * {@link SessionRenewedEvent}. Empty when it does not exist or expired.
     */
    public Optional<SessionRecord> touch(String sessionId) {
        Optional<SessionRecord> session = this.peek(sessionId);
        session.ifPresent(s -> {
            long left = cacheService.getExpire(sessionKey(sessionId));
            if (left >= 0 && left < SLIDE_BELOW_SECONDS) {
                cacheService.expire(sessionKey(sessionId), TTL_SECONDS);
                cacheService.expire(accountKey(s.accountId()), TTL_SECONDS);
                eventPublisher.publishEvent(new SessionRenewedEvent(sessionId, s));
            }
        });
        return session;
    }

    /**
     * The session as it was, when it ended in the last minute because its organisation became
     * unavailable. Empty otherwise — never expired, ended for another reason, or too long ago.
     */
    public Optional<SessionRecord> endedForUnavailableOrg(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(cacheService.get(ORG_UNAVAILABLE_SESSIONS + sessionId, SessionRecord.class));
        } catch (JSONException e) {
            return Optional.empty();
        }
    }

    /**
     * Remove one session.
     *
     * @return whether a live session was removed
     */
    public boolean revoke(String sessionId, SessionRevokeReason reason) {
        Optional<SessionRecord> session = this.peek(sessionId);
        // Marked before the session goes, so a concurrent request never sees neither.
        if (reason == SessionRevokeReason.ORG_UNAVAILABLE) {
            session.ifPresent(s -> cacheService.save(ORG_UNAVAILABLE_SESSIONS + sessionId, s,
                    ORG_UNAVAILABLE_MARK_SECONDS));
        }
        // Two requests can both read the session before either removes it; only the one whose delete
        // took the key announces the removal, so its listeners (the login history) record it once.
        Long deleted = cacheService.clear(List.of(sessionKey(sessionId)));
        boolean removed = session.isPresent() && deleted != null && deleted > 0;
        if (removed) {
            SessionRecord s = session.get();
            cacheService.removeFromSet(accountKey(s.accountId()), sessionId);
            eventPublisher.publishEvent(new SessionRevokedEvent(sessionId, s, reason));
        }
        return removed;
    }

    /**
     * Remove every session of one account, except {@code keepSessionId} when given.
     *
     * @return how many live sessions were removed
     */
    public int revokeAccount(Long accountId, String keepSessionId, SessionRevokeReason reason) {
        if (accountId == null) {
            return 0;
        }
        int revoked = 0;
        for (String sessionId : cacheService.getSetMembers(accountKey(accountId))) {
            if (Objects.equals(sessionId, keepSessionId)) {
                continue;
            }
            if (this.revoke(sessionId, reason)) {
                revoked++;
            } else {
                // Expired on its own; the set still named it.
                cacheService.removeFromSet(accountKey(accountId), sessionId);
            }
        }
        return revoked;
    }

    /**
     * Remove every session of several accounts — typically all accounts of one person — except
     * {@code keepSessionId} when given.
     *
     * @return how many live sessions were removed
     */
    public int revokeAccounts(Collection<Long> accountIds, String keepSessionId, SessionRevokeReason reason) {
        if (accountIds == null) {
            return 0;
        }
        return accountIds.stream().filter(Objects::nonNull).distinct()
                .mapToInt(accountId -> this.revokeAccount(accountId, keepSessionId, reason))
                .sum();
    }
}
