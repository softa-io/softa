package io.softa.starter.user.service.impl;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.framework.web.session.SessionStore;
import io.softa.starter.user.service.UserAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A session ended because its organisation became unavailable offers a sign-in token into the person's other
 * companies every time it is presented. A credential change must end that offer: the real session store, the real
 * revocation and the real token check together, on one in-memory cache — not each against a mock of the others,
 * which is how a fifteen-minute credential mark beside a week-long session mark went unnoticed.
 */
class CredentialChangeEndsReentryTest {

    private static final Long PROFILE = 7L;
    private static final Long ACCOUNT = 100L;

    private final MapCache cache = new MapCache();
    private final SessionStore sessionStore = new SessionStore(cache, mock(ApplicationEventPublisher.class));
    private final UserSessionServiceImpl sessionService = new UserSessionServiceImpl();
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    CredentialChangeEndsReentryTest() {
        UserAccountService accounts = mock(UserAccountService.class);
        when(accounts.listAccountIdsOf(PROFILE)).thenReturn(List.of(ACCOUNT));
        ReflectionTestUtils.setField(sessionService, "sessionStore", sessionStore);
        ReflectionTestUtils.setField(sessionService, "cacheService", cache);
        ReflectionTestUtils.setField(sessionService, "accountService", accounts);
        ReflectionTestUtils.setField(loginService, "sessionService", sessionService);
        ReflectionTestUtils.setField(loginService, "cacheService", cache);
    }

    /** A session of the person's, ended because its organisation became unavailable. */
    private String endedSession() {
        String sessionId = sessionStore.create(ACCOUNT, PROFILE, 1L, null, null, null, null);
        sessionStore.revoke(sessionId, SessionRevokeReason.ORG_UNAVAILABLE);
        return sessionId;
    }

    /** What presenting the ended session yields: a token into the other companies, or null (SESSION_EXPIRED). */
    private String present(String sessionId) {
        return sessionStore.endedForUnavailableOrg(sessionId)
                .map(ended -> loginService.issueReentryToken(ended.profileId(), ended.issuedAt()))
                .orElse(null);
    }

    private static void assertRefused(Runnable step) {
        assertThatThrownBy(step::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(io.softa.starter.user.constant.AccountErrorCodes.AUTH_TOKEN_EXPIRED);
    }

    @Test
    void withoutACredentialChange_theEndedSessionStillOffersTheOtherCompanies() {
        String sessionId = endedSession();

        assertThat(present(sessionId)).isNotNull();
    }

    @Test
    void aPasswordChange_takesBackTheOffer_soTheEndedSessionReadsAsExpired() {
        String sessionId = endedSession();
        assertThat(present(sessionId)).isNotNull();

        sessionService.revokePerson(PROFILE, null, SessionRevokeReason.PASSWORD_RESET);

        assertThat(sessionStore.endedForUnavailableOrg(sessionId)).isEmpty();
        assertThat(present(sessionId)).isNull();
    }

    @Test
    void aTokenTakenBeforeThePasswordChange_isRefused_evenOnceTheCredentialMarkIsGone() {
        String sessionId = endedSession();
        String token = present(sessionId);

        sessionService.revokePerson(PROFILE, null, SessionRevokeReason.RESET_USER);
        assertRefused(() -> loginService.selectTenant(token, ACCOUNT));

        // The credential mark expiring (simulated) changes nothing: the mark it would have had to refuse is gone,
        // and presenting the session again mints no new token.
        cache.clear("login:credentials-changed:" + PROFILE);
        assertThat(present(sessionId)).isNull();
    }

    @Test
    void aMarkThatSurvivedTheChange_stillYieldsOnlyRefusedTokens_forAsLongAsASessionLives() {
        // Belt and braces: were a mark to survive the change (written by an org-unavailable revoke racing it),
        // the credential mark outlives any session, so every token it offers is refused.
        String sessionId = endedSession();
        Object ended = cache.object(SessionStore.ORG_UNAVAILABLE_SESSIONS + sessionId);
        sessionService.revokePerson(PROFILE, null, SessionRevokeReason.PASSWORD_RESET);
        assertThat(cache.ttl("login:credentials-changed:" + PROFILE)).isGreaterThan(SessionStore.TTL_SECONDS);

        cache.save(SessionStore.ORG_UNAVAILABLE_SESSIONS + sessionId, ended, 60);
        String token = present(sessionId);

        assertThat(token).isNotNull();
        assertRefused(() -> loginService.selectTenant(token, ACCOUNT));
    }

    /** Just enough of Redis for the store, the revocation and the token: values, sets, TTLs. */
    private static final class MapCache implements CacheService {
        private final Map<String, Object> values = new HashMap<>();
        private final Map<String, Long> ttls = new HashMap<>();

        long ttl(String key) {
            return ttls.getOrDefault(key, -2L);
        }

        Object object(String key) {
            return values.get(key);
        }

        @Override public String getKeyPath(String key) { return key; }
        @Override public void save(String key, Object object) { values.put(key, object); ttls.put(key, -1L); }
        @Override public void save(String key, Object object, int expireSeconds) {
            if (object == null) {
                return;
            }
            values.put(key, object);
            ttls.put(key, (long) expireSeconds);
        }
        @Override public Map<String, Object> search(List<String> keys) {
            Map<String, Object> found = new HashMap<>();
            keys.forEach(key -> { if (values.containsKey(key)) { found.put(key, values.get(key)); } });
            return found;
        }
        @Override public boolean hasKey(String key) { return values.containsKey(key); }
        @Override public String get(String key) {
            Object value = values.get(key);
            return value == null ? null : String.valueOf(value);
        }
        @Override @SuppressWarnings("unchecked") public <T> T get(String key, Class<T> tClass) {
            return (T) values.get(key);
        }
        @Override @SuppressWarnings("unchecked") public <T> T get(String key, TypeReference<T> typeReference) {
            return (T) values.get(key);
        }
        @Override public <T> T get(String key, TypeReference<T> typeReference, T defaultValue) {
            T value = get(key, typeReference);
            return value == null ? defaultValue : value;
        }
        @Override public Long increment(String key, long expiredSeconds) {
            long next = Long.parseLong(String.valueOf(values.getOrDefault(key, "0"))) + 1;
            values.put(key, String.valueOf(next));
            return next;
        }
        @Override public void clear(String key) { values.remove(key); ttls.remove(key); }
        @Override public String getAndClear(String key) {
            String value = get(key);
            clear(key);
            return value;
        }
        @Override public Long clear(List<String> keys) {
            long removed = keys.stream().filter(values::containsKey).count();
            keys.forEach(this::clear);
            return removed;
        }
        @Override public long clearByPrefix(String prefix) {
            List<String> keys = values.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
            return clear(keys);
        }
        @Override public long getExpire(String key) { return ttl(key); }
        @Override public void expire(String key, int expireSeconds) {
            if (values.containsKey(key)) {
                ttls.put(key, (long) expireSeconds);
            }
        }
        @Override @SuppressWarnings("unchecked") public void addToSet(String key, String member, int expireSeconds) {
            ((Set<String>) values.computeIfAbsent(key, k -> new LinkedHashSet<String>())).add(member);
            ttls.put(key, (long) expireSeconds);
        }
        @Override public boolean addToBoundedSet(String key, String member, int maxSize, int expireSeconds) {
            addToSet(key, member, expireSeconds);
            return true;
        }
        @Override @SuppressWarnings("unchecked") public void removeFromSet(String key, String member) {
            Object set = values.get(key);
            if (set instanceof Set<?>) {
                ((Set<String>) set).remove(member);
            }
        }
        @Override @SuppressWarnings("unchecked") public Set<String> getSetMembers(String key) {
            Object set = values.get(key);
            return set instanceof Set<?> ? new LinkedHashSet<>((Set<String>) set) : Set.of();
        }
    }
}
