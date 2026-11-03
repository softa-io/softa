package io.softa.framework.web.filter.context;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.UserNotFoundException;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.framework.orm.service.UserInfoService;
import io.softa.framework.web.session.ReentryTokenIssuer;
import io.softa.framework.web.session.SessionErrorCodes;
import io.softa.framework.web.session.SessionRecord;
import io.softa.framework.web.session.SessionStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code user-info:{userId}} is a cache, and every eviction in this codebase is written as a
 * refresh: an invitee flipping INVITED → ACTIVE, a bulk unfreeze, a person renaming themselves, an
 * HR-side employee change. Each documents the stale value it means to replace.
 *
 * <p>This gate is the one place that decided otherwise. Reading the entry and answering a miss with
 * "user not found" turned all of those refreshes into a forced logout of the very user being
 * updated — and an unrecoverable one, because {@code /UserProfile/getMyUserInfo}, the endpoint that
 * would rebuild the entry, sits behind this same gate. Only a fresh login could restore it.
 */
class ContextBuilderUserInfoRebuildTest {

    private static final String SESSION_ID = "sess-1";
    private static final Long USER_ID = 42L;

    private CacheService cacheService;
    private UserInfoService userInfoService;
    private ContextBuilder contextBuilder;

    @BeforeEach
    void setUp() {
        SystemConfig config = new SystemConfig();
        config.setDebug(false);
        // Single-tenant: setMultiTenancyEnv is a separate gate with its own tests, and leaving it
        // out keeps these assertions about the user-info read alone.
        config.setEnableMultiTenancy(false);
        SystemConfig.env = config;

        cacheService = mock(CacheService.class);
        userInfoService = mock(UserInfoService.class);
        contextBuilder = new ContextBuilder();
        ReflectionTestUtils.setField(contextBuilder, "cacheService", cacheService);
        ReflectionTestUtils.setField(contextBuilder, "userInfoService", userInfoService);
        ReflectionTestUtils.setField(contextBuilder, "sessionStore",
                new SessionStore(cacheService, mock(ApplicationEventPublisher.class)));

        when(cacheService.get(eq(RedisConstant.SESSION + SESSION_ID), eq(SessionRecord.class)))
                .thenReturn(new SessionRecord(USER_ID, 7L, 9L, null, null, null, LocalDateTime.now()));
        when(cacheService.getExpire(RedisConstant.SESSION + SESSION_ID)).thenReturn((long) SessionStore.TTL_SECONDS);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(BaseConstant.SESSION_ID_HEADER, SESSION_ID);
        return request;
    }

    private static UserInfo userInfo(Boolean active) {
        UserInfo info = new UserInfo();
        info.setUserId(USER_ID);
        info.setName("Ada");
        info.setActive(active);
        return info;
    }

    private void cachedUserInfo(UserInfo info) {
        when(cacheService.get(eq(RedisConstant.USER_INFO + USER_ID), eq(UserInfo.class))).thenReturn(info);
    }

    @Test
    void servesTheCachedEntryWithoutTouchingTheDatabase() {
        cachedUserInfo(userInfo(true));

        Context context = contextBuilder.buildUserContext(request());

        assertThat(context.getUserId()).isEqualTo(USER_ID);
        verify(userInfoService, never()).loadUserInfo(any());
    }

    @Test
    void rebuildsAColdEntryInsteadOfRejectingTheSession() {
        // The evicted state. Before this, the request died here and the caller was sent to re-login.
        cachedUserInfo(null);
        when(userInfoService.loadUserInfo(USER_ID)).thenReturn(userInfo(true));

        Context context = contextBuilder.buildUserContext(request());

        assertThat(context.getUserId()).isEqualTo(USER_ID);
        assertThat(context.getName()).isEqualTo("Ada");
        verify(userInfoService).loadUserInfo(USER_ID);
    }

    @Test
    void aRebuiltFrozenAccountIsStillForceLoggedOut() {
        // The reason the eviction exists at all. The gate can only read `active` off an entry that
        // is present, so rebuilding is what finally lets it fire — before, the miss short-circuited
        // past it and every account got the same treatment, frozen or not.
        cachedUserInfo(null);
        when(userInfoService.loadUserInfo(USER_ID)).thenReturn(userInfo(false));

        assertThatThrownBy(() -> contextBuilder.buildUserContext(request()))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining("not active")
                // Only this organisation is unavailable — the client re-runs the entry rule rather
                // than sending the person back to the login page.
                .extracting(e -> ((UserNotFoundException) e).getErrorCode())
                .isEqualTo(SessionErrorCodes.ORG_UNAVAILABLE);

        // And this one really is over: the session is destroyed, not merely unresolvable.
        verify(cacheService).clear(RedisConstant.SESSION + SESSION_ID);
        verify(cacheService).removeFromSet(SessionStore.ACCOUNT_SESSIONS + USER_ID, SESSION_ID);
    }

    @Test
    void aRebuiltUserInAnInactiveTenantIsStillForceLoggedOut() {
        // The rebuild runs cross-tenant, which makes buildUserInfo's own tenant assertion inert.
        // This gate is what carries it instead — and it is the stronger of the two, because it
        // destroys the session rather than just refusing the request.
        SystemConfig config = new SystemConfig();
        config.setDebug(false);
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
        TenantInfoService tenantInfoService = mock(TenantInfoService.class);
        when(tenantInfoService.isTenantActive(any())).thenReturn(false);
        ReflectionTestUtils.setField(contextBuilder, "tenantInfoService", tenantInfoService);

        UserInfo rebuilt = userInfo(true);
        rebuilt.setTenantId(9L);
        cachedUserInfo(null);
        when(userInfoService.loadUserInfo(USER_ID)).thenReturn(rebuilt);

        assertThatThrownBy(() -> contextBuilder.buildUserContext(request()))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining("is not active")
                .extracting(e -> ((UserNotFoundException) e).getErrorCode())
                .isEqualTo(SessionErrorCodes.ORG_UNAVAILABLE);

        verify(cacheService).clear(RedisConstant.SESSION + SESSION_ID);
    }

    @Test
    void anUnavailableOrganisation_handsBackASignInTokenForTheOtherCompanies() {
        // The person is still who they were: with the token the APP offers their other companies
        // instead of a login screen, which is what tells ORG_UNAVAILABLE from SESSION_EXPIRED.
        ReentryTokenIssuer issuer = profileId -> "reentry-for-" + profileId;
        @SuppressWarnings("unchecked")
        ObjectProvider<ReentryTokenIssuer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(issuer);
        ReflectionTestUtils.setField(contextBuilder, "reentryTokenIssuer", provider);
        cachedUserInfo(null);
        when(userInfoService.loadUserInfo(USER_ID)).thenReturn(userInfo(false));

        assertThatThrownBy(() -> contextBuilder.buildUserContext(request()))
                .isInstanceOf(UserNotFoundException.class)
                .extracting(e -> ((UserNotFoundException) e).getErrorDetails())
                .isEqualTo(java.util.Map.of("authToken", "reentry-for-7"));
    }

    @Test
    void aSessionOpenedForThePreviousHolder_endsOnceTheAccountBelongsToSomeoneElse() {
        // The session was opened for person 7; the account was closed, reopened and joined by 8.
        UserInfo now = userInfo(true);
        now.setProfileId(8L);
        cachedUserInfo(now);

        assertThatThrownBy(() -> contextBuilder.buildUserContext(request()))
                .isInstanceOf(UserNotFoundException.class)
                .extracting(e -> ((UserNotFoundException) e).getErrorCode())
                .isEqualTo(SessionErrorCodes.SESSION_EXPIRED);
        verify(cacheService).clear(RedisConstant.SESSION + SESSION_ID);
        // Read off the cached entry: no extra query per request.
        verify(userInfoService, never()).loadUserInfo(any());
    }

    @Test
    void theSamePerson_orAnEntryCachedBeforeTheFieldExisted_passes() {
        UserInfo same = userInfo(true);
        same.setProfileId(7L);
        cachedUserInfo(same);
        assertThat(contextBuilder.buildUserContext(request()).getUserId()).isEqualTo(USER_ID);

        cachedUserInfo(userInfo(true));
        assertThat(contextBuilder.buildUserContext(request()).getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void aSessionNamingAMissingUserIsStillRejected() {
        // The account or profile is gone. Nothing to rebuild, so the original answer stands.
        cachedUserInfo(null);
        when(userInfoService.loadUserInfo(USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> contextBuilder.buildUserContext(request()))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining("User info not found");
    }

    @Test
    void withoutAProviderAColdEntryStillRejects() {
        // A deployment without user-starter has no UserInfo to build. Degrades to the old behaviour
        // rather than NPE-ing on the optional bean.
        ReflectionTestUtils.setField(contextBuilder, "userInfoService", null);
        cachedUserInfo(null);

        assertThatThrownBy(() -> contextBuilder.buildUserContext(request()))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining("User info not found");
    }

    @Test
    void aLegacyEntryWithNoActiveFlagIsLeftAlone() {
        // Serialized before `active` existed. Treated as active so a deploy does not mass-logout.
        cachedUserInfo(userInfo(null));

        Context context = contextBuilder.buildUserContext(request());

        assertThat(context.getUserId()).isEqualTo(USER_ID);
        verify(cacheService, never()).clear(RedisConstant.SESSION + SESSION_ID);
    }

    @Test
    void anInvalidSessionNeverReachesTheRebuild() {
        when(cacheService.get(eq(RedisConstant.SESSION + SESSION_ID), eq(SessionRecord.class))).thenReturn(null);

        assertThatThrownBy(() -> contextBuilder.buildUserContext(request()))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining("Invalid session ID")
                .extracting(e -> ((UserNotFoundException) e).getErrorCode())
                .isEqualTo(SessionErrorCodes.SESSION_EXPIRED);

        verify(userInfoService, never()).loadUserInfo(any());
    }

    @Test
    void aConcurrentRequestArrivingAfterTheOrgEndedTheSession_isToldTheOrgIsUnavailableToo() {
        // The first of two simultaneous requests found the account frozen and ended the session.
        // The second finds no session; it must not answer SESSION_EXPIRED, or a client acting on
        // whichever answer lands first sends the person to the login page.
        ReentryTokenIssuer issuer = profileId -> "reentry-for-" + profileId;
        @SuppressWarnings("unchecked")
        ObjectProvider<ReentryTokenIssuer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(issuer);
        ReflectionTestUtils.setField(contextBuilder, "reentryTokenIssuer", provider);
        when(cacheService.get(eq(RedisConstant.SESSION + SESSION_ID), eq(SessionRecord.class))).thenReturn(null);
        when(cacheService.get(eq(SessionStore.ORG_UNAVAILABLE_SESSIONS + SESSION_ID), eq(SessionRecord.class)))
                .thenReturn(new SessionRecord(USER_ID, 7L, 9L, null, null, null, LocalDateTime.now()));

        assertThatThrownBy(() -> contextBuilder.buildUserContext(request()))
                .isInstanceOf(UserNotFoundException.class)
                .satisfies(e -> {
                    UserNotFoundException u = (UserNotFoundException) e;
                    assertThat(u.getErrorCode()).isEqualTo(SessionErrorCodes.ORG_UNAVAILABLE);
                    assertThat(u.getErrorDetails()).isEqualTo(java.util.Map.of("authToken", "reentry-for-7"));
                });
    }

    @Test
    void aSessionThatIsSimplyGone_isStillExpired() {
        when(cacheService.get(eq(RedisConstant.SESSION + SESSION_ID), eq(SessionRecord.class))).thenReturn(null);

        assertThatThrownBy(() -> contextBuilder.buildUserContext(request()))
                .isInstanceOf(UserNotFoundException.class)
                .extracting(e -> ((UserNotFoundException) e).getErrorCode())
                .isEqualTo(SessionErrorCodes.SESSION_EXPIRED);
    }
}
