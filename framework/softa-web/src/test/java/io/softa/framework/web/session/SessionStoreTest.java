package io.softa.framework.web.session;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.exception.JSONException;
import io.softa.framework.orm.service.CacheService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sessions are indexed by account, slide on use, and announce every removal.
 */
class SessionStoreTest {

    private static final Long ACCOUNT = 100L;
    private static final Long PROFILE = 7L;

    private final CacheService cacheService = mock(CacheService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final SessionStore store = new SessionStore(cacheService, publisher);

    private static SessionRecord record(Long accountId) {
        return new SessionRecord(accountId, PROFILE, 1L, "ios", "1.2.0", null, LocalDateTime.now());
    }

    private void live(String sessionId, Long accountId, long secondsLeft) {
        when(cacheService.get(RedisConstant.SESSION + sessionId, SessionRecord.class)).thenReturn(record(accountId));
        when(cacheService.getExpire(RedisConstant.SESSION + sessionId)).thenReturn(secondsLeft);
        when(cacheService.clear(List.of(RedisConstant.SESSION + sessionId))).thenReturn(1L);
    }

    @Test
    void create_writesTheRecordForSevenDays_andIndexesItUnderTheAccount() {
        String sessionId = store.create(ACCOUNT, PROFILE, 1L, "android", "1.0.0", "Pixel 8");

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(cacheService).save(eq(RedisConstant.SESSION + sessionId), value.capture(), eq(RedisConstant.ONE_WEEK));
        SessionRecord saved = (SessionRecord) value.getValue();
        assertThat(saved.accountId()).isEqualTo(ACCOUNT);
        assertThat(saved.profileId()).isEqualTo(PROFILE);
        assertThat(saved.platform()).isEqualTo("android");
        verify(cacheService).addToSet("account-sessions:" + ACCOUNT, sessionId, RedisConstant.ONE_WEEK);
    }

    @Test
    void touch_slidesOnlyOnceLessThanSixDaysAreLeft() {
        live("fresh", ACCOUNT, RedisConstant.ONE_WEEK - 60);
        store.touch("fresh");
        // At most one Redis write a day for an active client.
        verify(cacheService, never()).expire(anyString(), anyInt());
        verify(publisher, never()).publishEvent(any(SessionRenewedEvent.class));

        live("aging", ACCOUNT, RedisConstant.ONE_DAY * 5L);
        assertThat(store.touch("aging")).isPresent();
        verify(cacheService).expire(RedisConstant.SESSION + "aging", RedisConstant.ONE_WEEK);
        verify(cacheService).expire("account-sessions:" + ACCOUNT, RedisConstant.ONE_WEEK);
        // Announced once per slide, so a copy of "this session is alive" can follow it.
        verify(publisher).publishEvent(org.mockito.ArgumentMatchers.<SessionRenewedEvent>argThat(
                event -> event.sessionId().equals("aging") && event.session().accountId().equals(ACCOUNT)));
    }

    @Test
    void aValueOfAnEarlierShape_readsAsExpired() {
        // Earlier releases stored the bare account id; the release clears them, and a straggler
        // must not take the request down.
        when(cacheService.get(RedisConstant.SESSION + "legacy", SessionRecord.class))
                .thenThrow(new JSONException("cannot deserialize"));

        assertThat(store.touch("legacy")).isEmpty();
        verify(cacheService).clear(RedisConstant.SESSION + "legacy");
    }

    @Test
    void aRedisFailure_isNotTakenForAnUnreadableSession() {
        // A timeout says nothing about the session; deleting it would log a valid user out.
        when(cacheService.get(RedisConstant.SESSION + "s1", SessionRecord.class))
                .thenThrow(new IllegalStateException("Redis command timed out"));

        assertThatThrownBy(() -> store.touch("s1")).isInstanceOf(IllegalStateException.class);
        verify(cacheService, never()).clear(RedisConstant.SESSION + "s1");
    }

    @Test
    void create_dropsSessionsThatExpiredOnTheirOwnFromTheIndex() {
        when(cacheService.getSetMembers("account-sessions:" + ACCOUNT)).thenReturn(java.util.Set.of("gone", "alive"));
        when(cacheService.hasKey(RedisConstant.SESSION + "alive")).thenReturn(true);

        store.create(ACCOUNT, 7L, 1L, null, null, null);

        verify(cacheService).removeFromSet("account-sessions:" + ACCOUNT, "gone");
        verify(cacheService, never()).removeFromSet("account-sessions:" + ACCOUNT, "alive");
    }

    @Test
    void revoke_removesTheSessionAndItsIndexEntry_andAnnouncesWhy() {
        live("s1", ACCOUNT, 100);

        assertThat(store.revoke("s1", SessionRevokeReason.LOGOUT)).isTrue();

        verify(cacheService).clear(List.of(RedisConstant.SESSION + "s1"));
        verify(cacheService).removeFromSet("account-sessions:" + ACCOUNT, "s1");
        ArgumentCaptor<SessionRevokedEvent> event = ArgumentCaptor.forClass(SessionRevokedEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue().reason()).isEqualTo(SessionRevokeReason.LOGOUT);
        assertThat(event.getValue().session().accountId()).isEqualTo(ACCOUNT);
    }

    @Test
    void revokeAccount_endsEverySessionButTheOneKept() {
        when(cacheService.getSetMembers("account-sessions:" + ACCOUNT)).thenReturn(Set.of("keep", "a", "b", "gone"));
        live("keep", ACCOUNT, 100);
        live("a", ACCOUNT, 100);
        live("b", ACCOUNT, 100);

        int revoked = store.revokeAccount(ACCOUNT, "keep", SessionRevokeReason.KICKED_BY_OTHER_DEVICE);

        assertThat(revoked).isEqualTo(2);
        verify(cacheService).clear(List.of(RedisConstant.SESSION + "a"));
        verify(cacheService).clear(List.of(RedisConstant.SESSION + "b"));
        verify(cacheService, never()).clear(List.of(RedisConstant.SESSION + "keep"));
        // A member that had already expired is dropped from the index, without an event.
        verify(cacheService).removeFromSet("account-sessions:" + ACCOUNT, "gone");
    }

    @Test
    void theApp_isReadFromTheHeaderOnly_evenWhenItAlsoCarriesACookie() {
        // An iOS URLSession keeps cookies unless told not to: the header is what counts.
        MockHttpServletRequest app = new MockHttpServletRequest();
        app.addHeader(SessionStore.APP_PLATFORM_HEADER, "ios");
        app.addHeader(BaseConstant.SESSION_ID_HEADER, "from-header");
        app.setCookies(new jakarta.servlet.http.Cookie(BaseConstant.SESSION_ID, "from-cookie"));
        assertThat(SessionStore.sessionIdOf(app)).isEqualTo("from-header");

        MockHttpServletRequest appWithCookieOnly = new MockHttpServletRequest();
        appWithCookieOnly.addHeader(SessionStore.APP_VERSION_HEADER, "1.0.0");
        appWithCookieOnly.setCookies(new jakarta.servlet.http.Cookie(BaseConstant.SESSION_ID, "from-cookie"));
        assertThat(SessionStore.sessionIdOf(appWithCookieOnly)).isNull();
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void web_isReadFromTheCookieOnly_aHeaderIsIgnored() {
        MockHttpServletRequest web = new MockHttpServletRequest();
        web.setCookies(new jakarta.servlet.http.Cookie(BaseConstant.SESSION_ID, "from-cookie"));
        web.addHeader(BaseConstant.SESSION_ID_HEADER, "from-header");
        assertThat(SessionStore.sessionIdOf(web)).isEqualTo("from-cookie");

        MockHttpServletRequest headerOnly = new MockHttpServletRequest();
        headerOnly.addHeader(BaseConstant.SESSION_ID_HEADER, "from-header");
        assertThat(SessionStore.sessionIdOf(headerOnly)).isNull();
        assertThat(SessionStore.sessionIdOf(new MockHttpServletRequest())).isNull();
    }

    @Test
    void aSessionServesOnlyTheKindOfClientItWasIssuedTo() {
        SessionRecord appSession = new SessionRecord(ACCOUNT, 7L, 1L, "ios", "1.0.0", null, LocalDateTime.now());
        SessionRecord webSession = new SessionRecord(ACCOUNT, 7L, 1L, null, null, null, LocalDateTime.now());
        MockHttpServletRequest app = new MockHttpServletRequest();
        app.addHeader(SessionStore.APP_PLATFORM_HEADER, "android");
        MockHttpServletRequest web = new MockHttpServletRequest();

        assertThat(SessionStore.sameClient(appSession, app)).isTrue();
        assertThat(SessionStore.sameClient(webSession, web)).isTrue();
        assertThat(SessionStore.sameClient(webSession, app)).isFalse();
        assertThat(SessionStore.sameClient(appSession, web)).isFalse();
        // Only a version said, no platform: still the APP, on both sides.
        SessionRecord versionOnly = new SessionRecord(ACCOUNT, 7L, 1L, null, "1.0.0", null, LocalDateTime.now());
        assertThat(versionOnly.isApp()).isTrue();
    }

    @Test
    void endingASessionForAnUnavailableOrg_leavesAShortLivedMark_otherReasonsDoNot() {
        live("frozen", ACCOUNT, RedisConstant.ONE_WEEK);
        live("loggedOut", ACCOUNT, RedisConstant.ONE_WEEK);

        store.revoke("frozen", SessionRevokeReason.ORG_UNAVAILABLE);
        store.revoke("loggedOut", SessionRevokeReason.LOGOUT);

        verify(cacheService).save(eq(SessionStore.ORG_UNAVAILABLE_SESSIONS + "frozen"), any(SessionRecord.class),
                eq(RedisConstant.ONE_MINUTES));
        verify(cacheService, never()).save(eq(SessionStore.ORG_UNAVAILABLE_SESSIONS + "loggedOut"), any(), anyInt());
    }

    @Test
    void endedForUnavailableOrg_readsTheMark() {
        when(cacheService.get(SessionStore.ORG_UNAVAILABLE_SESSIONS + "frozen", SessionRecord.class))
                .thenReturn(record(ACCOUNT));

        assertThat(store.endedForUnavailableOrg("frozen")).map(SessionRecord::profileId).contains(PROFILE);
        assertThat(store.endedForUnavailableOrg("other")).isEmpty();
        assertThat(store.endedForUnavailableOrg(null)).isEmpty();
    }

    @Test
    void twoRequestsRevokingTheSameSession_announceItOnce() {
        live("s1", ACCOUNT, 100);
        // The second caller read the session too, but its delete found the key already gone.
        when(cacheService.clear(List.of(RedisConstant.SESSION + "s1"))).thenReturn(1L, 0L);

        assertThat(store.revoke("s1", SessionRevokeReason.ORG_UNAVAILABLE)).isTrue();
        assertThat(store.revoke("s1", SessionRevokeReason.ORG_UNAVAILABLE)).isFalse();

        verify(publisher).publishEvent(any(SessionRevokedEvent.class));
    }
}
