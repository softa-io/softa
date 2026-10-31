package io.softa.framework.web.session;

import java.time.LocalDateTime;
import java.util.Collection;
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

    /**
     * The session id a request carries: the cookie (Web) first, then the {@code X-Session-Id}
     * header (APP). Null when it carries neither.
     */
    public static String sessionIdOf(HttpServletRequest request) {
        String sessionId = CookieUtils.getCookie(request, BaseConstant.SESSION_ID);
        if (StringUtils.isBlank(sessionId)) {
            sessionId = request.getHeader(BaseConstant.SESSION_ID_HEADER);
        }
        return StringUtils.isBlank(sessionId) ? null : sessionId;
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
     * Read a session and slide its expiry when it is due. Empty when it does not exist or expired.
     */
    public Optional<SessionRecord> touch(String sessionId) {
        Optional<SessionRecord> session = this.peek(sessionId);
        session.ifPresent(s -> {
            long left = cacheService.getExpire(sessionKey(sessionId));
            if (left >= 0 && left < SLIDE_BELOW_SECONDS) {
                cacheService.expire(sessionKey(sessionId), TTL_SECONDS);
                cacheService.expire(accountKey(s.accountId()), TTL_SECONDS);
            }
        });
        return session;
    }

    /**
     * Remove one session.
     *
     * @return whether a live session was removed
     */
    public boolean revoke(String sessionId, SessionRevokeReason reason) {
        Optional<SessionRecord> session = this.peek(sessionId);
        cacheService.clear(sessionKey(sessionId));
        session.ifPresent(s -> {
            cacheService.removeFromSet(accountKey(s.accountId()), sessionId);
            eventPublisher.publishEvent(new SessionRevokedEvent(sessionId, s, reason));
        });
        return session.isPresent();
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
